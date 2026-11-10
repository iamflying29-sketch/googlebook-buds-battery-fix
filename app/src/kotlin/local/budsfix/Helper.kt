package local.budsfix

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Buds Battery Fix helper. Runs on the device as the adb shell user (started by budsfix.sh with
 * app_process, code taken from the installed Buds Battery Fix app), so it needs no app to be open
 * and no Linux Terminal.
 *
 * What it fixes: Google Play services' Fast Pair sometimes receives a battery advertisement from
 * the earbuds that marks BOTH buds as "unknown, charging" (byte 0xFF) while the case has a real
 * level. Fast Pair stores that, so every screen that reads the stored levels (the Batteries widget,
 * Settings, Quick Settings) shows only the case until the next real report arrives.
 *
 * What it does: it listens for every change of the stored battery values of each paired Fast Pair
 * true wireless headset. When a change produces exactly that bad pattern, it puts back the buds'
 * latest real levels and charging states, which the earbuds themselves reported shortly before.
 * The reading counts as current if it arrived within MAX_AGE_MS, or if the earbuds have stayed
 * connected ever since (while connected, every change is reported). Otherwise it leaves the values
 * alone. Values that were already stored when the helper started are never used: their age is
 * unknown. The new case value is kept.
 * Every other change, real or unknown, passes through untouched, so any real report from the
 * earbuds always wins at once. It never invents a number and holds no wake lock.
 *
 * Second job, LE Audio auto-connect for the Pixel Buds: on a multi-user device, Bluetooth first starts
 * for the system user at boot. If that user's LE Audio setting for the buds is "forbidden", it takes
 * them off LE Audio's auto-connect list and saves that. When Bluetooth then starts for the person's
 * own user, its step that puts allowed devices back on the list can run before the buds are loaded
 * (seen on the Googlebook: 2 ms too early), so they are never put back and do not auto-connect. Once
 * per Bluetooth start, when Bluetooth has run for BT_SETTLE_MS, this helper looks at the buds' LE
 * Audio state in Bluetooth's own log. If they are already on the auto-connect list or connected, it
 * changes nothing. Otherwise, and only if their LE Audio setting already is "allowed" (100), it
 * re-applies that setting: the same call as the LE Audio switch in Settings, which puts the buds back
 * on the auto-connect list and asks LE Audio to connect them. If they are in the case, the request
 * times out after 30 s and they stay on the list. It then checks the log again to confirm. If the log
 * cannot be read or its wording changed in an update, it re-applies the setting anyway, which was
 * proven harmless in both states. Classic audio is never touched.
 *
 * Self-check, so an update can never break it silently: at every start it checks everything it relies
 * on and writes the result to HEALTH_FILE ("ok", "info" and "attention" lines). budsfix.sh status
 * shows the attention lines, and the app shows "Needs attention" with a notification. It records the
 * system build and the Bluetooth and Play services versions, and logs when an update changed them.
 */
object Helper {
    // BluetoothDevice metadata keys (Android's public constants).
    private const val MODEL_NAME = 1
    private const val UNTETHERED = 6
    private const val LEFT = 10
    private const val RIGHT = 11
    private const val CASE = 12
    private const val LEFT_CHARGING = 13
    private const val RIGHT_CHARGING = 14
    private const val CASE_CHARGING = 15
    private val WATCHED = setOf(LEFT, RIGHT, CASE, LEFT_CHARGING, RIGHT_CHARGING, CASE_CHARGING)

    /** A real reading older than this is not put back (it would no longer be current). */
    private const val MAX_AGE_MS = 10 * 60_000L
    /** Fast Pair writes left, left charging, right, right charging within about 15 ms. */
    private const val SETTLE_MS = 30L
    /** How often to look for newly paired headsets and check each one's connection. */
    private const val RESCAN_MS = 30_000L
    /** Any Bluetooth call that takes longer than this means something is wrong: exit and be restarted. */
    private const val STALL_MS = 20_000L

    // LE Audio auto-connect (second job). Profile number and values from BluetoothProfile.
    private const val LE_AUDIO = 22
    private const val POLICY_ALLOWED = 100
    private const val STATE_CONNECTING = 1
    private const val STATE_CONNECTED = 2
    /** Bluetooth must have run this long, so LE Audio has loaded the paired earbuds. */
    private const val BT_SETTLE_MS = 10_000L
    /** How long LE Audio may take to answer the profile request. */
    private const val PROXY_WAIT_MS = 15_000L
    /** LE Audio's connection request times out after 30 s; confirm the outcome after this. */
    private const val VERIFY_AFTER_MS = 35_000L
    /** Limits for reading Bluetooth's log: total time and size. */
    private const val LOG_DEADLINE_MS = 10_000L
    private const val LOG_CAP_BYTES = 256 * 1024
    /** Bluetooth's process names, plus a pattern for a renamed one (checked against its user id). */
    private val BT_NAMES = setOf("com.google.android.bluetooth", "com.android.bluetooth")
    private val BT_NAME_PATTERN = Regex("""^[a-z0-9_.]+\.bluetooth$""")
    private const val BT_APP_ID = 1002

    private const val DIR = "/data/local/tmp/budsfix"
    /** The Bluetooth start (process id and start time) the LE Audio step was last done for. */
    private var REARM_FILE = File("$DIR/le-audio-rearm.txt")
    /** The self-check's latest result, rewritten whenever it changes. */
    private var HEALTH_FILE = File("$DIR/health.txt")
    /** The versions seen at the last start, to notice updates. */
    private var VERSIONS_FILE = File("$DIR/versions.txt")
    /** Test run by hand ("test-rearm-force"): own state files, always re-applies, exits after confirming. */
    private var testForce = false

    private class Reading(val left: String, val leftCharging: String, val right: String, val rightCharging: String, val at: Long)

    private class Watched(val device: BluetoothDevice, val listener: Any) {
        /** The latest real bud reading Fast Pair stored while this helper was watching. */
        var lastReal: Reading? = null
        /** True while the earbuds have stayed connected at every check since lastReal arrived. */
        var connectedSinceReal = false
        val pending = mutableMapOf<Int, String>() // values we wrote ourselves and expect back
        val batch = mutableSetOf<Int>()           // keys Fast Pair changed in the batch being collected
    }

    private lateinit var adapter: BluetoothAdapter
    private var context: Context? = null
    private lateinit var listenerClass: Class<*>
    private lateinit var worker: Handler
    private val watched = mutableMapOf<String, Watched>()
    private val notPixelBuds = mutableSetOf<String>()
    @Volatile private var callStartedAt = 0L

    private var rearmBusy = false
    private var rearmToken: Any? = null
    private var rearmFor = ""
    private var rearmTries = 0
    private var missingBtProcess = 0
    private var updateNote: String? = null
    private var firstCheckDone = false

    private fun log(msg: String) {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        println("$t $msg")
        System.out.flush()
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ---- Self-check -------------------------------------------------------------------------------

    private val health = sortedMapOf<String, Pair<String, String>>() // item -> (level, text)

    /** Records one self-check item ("ok", "info" or "attention") and rewrites HEALTH_FILE if it changed. */
    @Synchronized
    private fun health(item: String, level: String, text: String) {
        val old = health[item]
        if (old == level to text) return
        health[item] = level to text
        if (level == "attention" && old?.first != "attention") log("needs attention: $text")
        if (level != "attention" && old?.first == "attention") log("resolved: $text")
        writeHealth()
    }

    @Synchronized
    private fun healthRemove(item: String) {
        if (health.remove(item) != null) writeHealth()
    }

    @Synchronized
    private fun writeHealth() {
        val body = StringBuilder("checked ${now()}\n")
        for (want in listOf("attention", "ok", "info")) for ((_, v) in health) if (v.first == want) body.append("${v.first}: ${v.second}\n")
        try {
            val tmp = File(HEALTH_FILE.path + ".new")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(HEALTH_FILE)) HEALTH_FILE.writeText(body.toString())
        } catch (e: Throwable) {
            log("could not write the self-check file: $e")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.firstOrNull() == "test-log-reader") { testLogReader(); System.exit(0) }
            if (args.firstOrNull() == "test-le-state") { testLeState(); System.exit(0) }
            if (args.firstOrNull() == "test-rearm-force") {
                testForce = true
                REARM_FILE = File("$DIR/test-le-audio-rearm.txt")
                HEALTH_FILE = File("$DIR/test-health.txt")
                VERSIONS_FILE = File("$DIR/test-versions.txt")
                try { REARM_FILE.writeText("none\n") } catch (_: Throwable) {}
            }
            run()
        } catch (e: Throwable) {
            // Exit at once: Android's own crash handler can stall in an app_process program.
            val why = "$e${e.cause?.let { " / $it" } ?: ""}"
            health("start", "attention", "the helper could not start on this Android version: $why")
            log("stopping after an error: $why")
            System.exit(1)
        }
    }

    private fun run() {
        Looper.prepareMainLooper()
        try {
            Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null)
        } catch (e: Throwable) {
            // Needed on Android 17 so Bluetooth's module can be reached. If an update removes it, say so
            // and try anyway: the adapter step below proves whether Bluetooth still opens.
            health("modules", "info", "Android's module setup call is not available on this version ($e)")
        }
        val way = shellAdapter()
        health("start", "ok", "opened Bluetooth as the adb shell user (way $way of 4)")
        listenerClass = Class.forName("android.bluetooth.BluetoothAdapter\$OnMetadataChangedListener")
        val thread = HandlerThread("budsfix").apply { start() }
        worker = Handler(thread.looper)
        startWatchdog()
        log("helper started (pid ${android.os.Process.myPid()}, Android ${android.os.Build.VERSION.RELEASE})")
        checkVersions()
        checkPermission()
        worker.post(::rescan)
        Looper.loop()
    }

    /** Records the system build and the Bluetooth and Play services versions, and notes any update. */
    private fun checkVersions() {
        val now = listOf(
            "system build" to android.os.Build.FINGERPRINT,
            "Bluetooth" to (versionOf("com.google.android.bluetooth") ?: versionOf("com.android.bluetooth") ?: "unknown"),
            "Google Play services" to (versionOf("com.google.android.gms") ?: "unknown"))
        val text = now.joinToString("\n") { "${it.first}: ${it.second}" }
        health("versions", "info", now.joinToString("; ") { "${it.first} ${it.second}" })
        val before = try { VERSIONS_FILE.readText().trim() } catch (_: Throwable) { "" }
        if (before.isNotEmpty() && before != text) {
            val old = before.lines().associate { it.substringBefore(": ") to it.substringAfter(": ") }
            val changes = now.filter { old[it.first] != null && old[it.first] != it.second }
                .joinToString("; ") { "${it.first} was ${old[it.first]} and is now ${it.second}" }
            if (changes.isNotEmpty()) {
                updateNote = changes
                log("an update was installed since the last start: $changes. Checking that everything still works.")
                health("update", "info", "updated before the start at ${now()}: $changes")
            }
        }
        try { VERSIONS_FILE.writeText("$text\n") } catch (e: Throwable) { log("could not save the versions: $e") }
    }

    private fun versionOf(pkg: String): String? = try {
        @Suppress("DEPRECATION")
        val pi = context!!.packageManager.getPackageInfo(pkg, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    } catch (_: Throwable) { null }

    /** The battery fix needs Android's Bluetooth privileges for the adb shell user. */
    private fun checkPermission() {
        val perm = "android.permission.BLUETOOTH_PRIVILEGED"
        val granted = try {
            context!!.checkPermission(perm, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            health("permission", "info", "could not check the adb shell user's Bluetooth privileges ($e)")
            return
        }
        if (granted) health("permission", "ok", "the adb shell user has Android's Bluetooth privileges")
        else health("permission", "attention", "the adb shell user no longer has Android's Bluetooth privileges, so the stored battery values cannot be corrected")
    }

    /** A Bluetooth adapter object that presents the adb shell user's identity. Returns which way worked. */
    private fun shellAdapter(): Int {
        val shell = AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
        val ctx = try { ShellContext(systemContext(), shell) } catch (e: Throwable) {
            health("context", "attention", "could not create a system context on this version ($e); the LE Audio step needs it")
            null
        }
        context = ctx
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "bluetooth_manager") as IBinder? ?: error("the Bluetooth manager service is not running")
        val mgrClass = Class.forName("android.bluetooth.IBluetoothManager")
        val mgr = Class.forName("android.bluetooth.IBluetoothManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val tries = listOf<() -> Any?>(
            { BluetoothAdapter::class.java.getDeclaredConstructor(mgrClass, Context::class.java, AttributionSource::class.java)
                .apply { isAccessible = true }.newInstance(mgr, ctx ?: error("no context"), shell) },
            { BluetoothAdapter::class.java.getDeclaredConstructor(mgrClass, AttributionSource::class.java)
                .apply { isAccessible = true }.newInstance(mgr, shell) },
            { BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", AttributionSource::class.java)
                .apply { isAccessible = true }.invoke(null, shell) },
            { BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", Context::class.java)
                .apply { isAccessible = true }.invoke(null, ctx ?: error("no context")) },
        )
        for ((i, t) in tries.withIndex()) {
            val a = try { t() } catch (_: Throwable) { null }
            if (a is BluetoothAdapter) { adapter = a; return i + 1 }
        }
        error("no way to open the Bluetooth adapter on this Android version")
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        return at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
    }

    private class ShellContext(base: Context, private val source: AttributionSource) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionSource() = source
        override fun getApplicationContext() = this
    }

    /** Runs one Bluetooth call under the watchdog. */
    private fun <T> call(block: () -> T): T {
        callStartedAt = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            callStartedAt = 0L
        }
    }

    private fun startWatchdog() {
        Thread({
            while (true) {
                SystemClock.sleep(2_000)
                val s = callStartedAt
                if (s != 0L && SystemClock.uptimeMillis() - s > STALL_MS) {
                    log("a Bluetooth call has not returned for ${STALL_MS / 1000} s; exiting so the supervisor starts a fresh helper")
                    Runtime.getRuntime().halt(4)
                }
            }
        }, "budsfix-watchdog").apply { isDaemon = true }.start()
    }

    private val getMeta = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
    private val setMeta = BluetoothDevice::class.java.getMethod("setMetadata", Int::class.javaPrimitiveType, ByteArray::class.java)

    private fun get(d: BluetoothDevice, key: Int): String? =
        call { (getMeta.invoke(d, key) as ByteArray?)?.toString(Charsets.UTF_8) }

    private fun set(d: BluetoothDevice, key: Int, value: String): Boolean =
        call { setMeta.invoke(d, key, value.toByteArray(Charsets.UTF_8)) as Boolean }

    private fun level(v: String?): Int = v?.trim()?.toIntOrNull() ?: -1

    private val isConnected = BluetoothDevice::class.java.getMethod("isConnected")
    private fun connected(d: BluetoothDevice): Boolean = try { call { isConnected.invoke(d) as Boolean } } catch (_: Throwable) { false }

    private fun isPixelBuds(d: BluetoothDevice): Boolean {
        if ((d.name ?: "").contains("Pixel Buds")) return true
        if (d.address in notPixelBuds) return false
        val model = try { get(d, MODEL_NAME) } catch (_: Throwable) { null }
        if ((model ?: "").contains("Pixel Buds")) return true
        notPixelBuds += d.address
        return false
    }

    /** Finds paired Fast Pair true wireless headsets and listens to each one. Repeats every 30 seconds. */
    private fun rescan() {
        try {
            if (call { adapter.state } != BluetoothAdapter.STATE_ON) {
                if (watched.isNotEmpty()) {
                    // Bluetooth went off: its listeners are gone. A fresh helper registers again.
                    log("Bluetooth is off; exiting so a fresh helper starts when it is back on")
                    System.exit(3)
                }
                health("bluetooth", "info", "Bluetooth is off; the checks continue when it is on")
            } else {
                health("bluetooth", "info", "Bluetooth is on")
                val bonded = call { adapter.bondedDevices } ?: emptySet()
                var pixelBuds = 0
                for (d in bonded) {
                    val pixel = isPixelBuds(d)
                    if (pixel) pixelBuds++
                    if (watched.containsKey(d.address)) continue
                    if (get(d, UNTETHERED) != "true") {
                        if (pixel) health("buds-${d.address}", "attention", "${d.name} are paired, but Fast Pair has not marked them as true " +
                            "wireless earbuds, so the battery fix cannot watch them")
                        continue
                    }
                    watch(d)
                }
                if (pixelBuds == 0) health("buds", "info", "no Pixel Buds are paired with this device")
                else health("buds", "ok", "$pixelBuds Pixel Buds paired")
                for (w in watched.values) if (w.connectedSinceReal && !connected(w.device)) w.connectedSinceReal = false
                val gone = watched.keys - bonded.map { it.address }.toSet()
                for (a in gone) { watched.remove(a); healthRemove("buds-$a"); log("$a is no longer paired") }
                maybeRearm()
                if (!firstCheckDone) {
                    firstCheckDone = true
                    val problems = synchronized(this) { health.values.count { it.first == "attention" } }
                    val summary = if (problems == 0) "all checks passed" else "$problems item(s) need attention"
                    log("self-check: $summary" + (updateNote?.let { " (after the update)" } ?: ""))
                }
            }
        } catch (e: Throwable) {
            log("rescan failed: $e")
        }
        worker.postDelayed(::rescan, RESCAN_MS)
    }

    private fun watch(d: BluetoothDevice) {
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
            when (method.name) {
                "onMetadataChanged" -> {
                    val key = a[1] as Int
                    if (key in WATCHED) {
                        val value = (a[2] as ByteArray?)?.toString(Charsets.UTF_8) ?: ""
                        val addr = (a[0] as BluetoothDevice).address
                        worker.post { onChanged(addr, key, value) }
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a[0]
                "toString" -> "BudsFixListener"
                else -> null
            }
        }
        val add = BluetoothAdapter::class.java.getMethod("addOnMetadataChangedListener",
            BluetoothDevice::class.java, Executor::class.java, listenerClass)
        val ok = call { add.invoke(adapter, d, Executor { it.run() }, listener) as Boolean }
        val w = Watched(d, listener)
        watched[d.address] = w
        log("watching ${d.name} (${d.address.takeLast(5)}): listener ${if (ok) "registered" else "NOT registered"}; " +
            "stored now ${describe(d)}")
        if (ok) health("buds-${d.address}", "ok", "watching the battery values of ${d.name}")
        else health("buds-${d.address}", "attention", "Android refused to report battery changes of ${d.name}, so the battery fix cannot work")
    }

    private fun describe(d: BluetoothDevice) =
        "L=${get(d, LEFT)}/${get(d, LEFT_CHARGING)} R=${get(d, RIGHT)}/${get(d, RIGHT_CHARGING)} C=${get(d, CASE)}/${get(d, CASE_CHARGING)}"

    private fun onChanged(addr: String, key: Int, value: String) {
        val w = watched[addr] ?: return
        val expected = w.pending[key]
        if (expected != null && expected == value) {
            w.pending.remove(key) // the echo of our own write
            return
        }
        w.batch += key
        // Wait until Fast Pair has finished writing this batch, then look at the whole picture.
        worker.removeCallbacksAndMessages(w)
        worker.postAtTime({ evaluate(w) }, w, SystemClock.uptimeMillis() + SETTLE_MS)
    }

    private fun evaluate(w: Watched) {
        try {
            val d = w.device
            val keys = w.batch.toSet(); w.batch.clear()
            val l = get(d, LEFT); val r = get(d, RIGHT); val c = get(d, CASE)
            val lc = get(d, LEFT_CHARGING); val rc = get(d, RIGHT_CHARGING)
            if (level(l) in 0..100 && level(r) in 0..100) {
                // A real reading: leave it exactly as Fast Pair wrote it. If Fast Pair just wrote the
                // buds' values, it is the latest real reading.
                if (keys.any { it == LEFT || it == RIGHT || it == LEFT_CHARGING || it == RIGHT_CHARGING }) {
                    w.lastReal = Reading(l!!, lc ?: "false", r!!, rc ?: "false", SystemClock.elapsedRealtime())
                    w.connectedSinceReal = connected(d)
                }
                return
            }
            val badPattern = level(l) == -1 && level(r) == -1 && lc == "true" && rc == "true" && level(c) in 0..100
            if (!badPattern) return // genuinely unknown or partial: leave it alone
            val real = w.lastReal
            if (real == null) {
                log("buds shown as unknown with the case at $c%, but no real bud reading is known yet; left as is")
                return
            }
            val age = SystemClock.elapsedRealtime() - real.at
            val connectedAllAlong = w.connectedSinceReal && connected(d)
            if (age > MAX_AGE_MS && !connectedAllAlong) {
                log("buds shown as unknown with the case at $c%; the last real bud reading is ${age / 1000} s old and the buds " +
                    "disconnected since, so it may no longer be current; left as is")
                return
            }
            val writes = listOf(LEFT to real.left, LEFT_CHARGING to real.leftCharging, RIGHT to real.right, RIGHT_CHARGING to real.rightCharging)
            var ok = true
            for ((key, value) in writes) {
                if (get(d, key) == value) continue
                w.pending[key] = value
                if (!set(d, key, value)) { ok = false; w.pending.remove(key) }
            }
            log("fixed: Fast Pair stored the buds as unknown with the case at $c%; put back the buds' latest real reading " +
                "L=${real.left}% R=${real.right}% (charging ${real.leftCharging}/${real.rightCharging}), reported ${age / 1000} s ago" +
                (if (connectedAllAlong) ", buds connected since" else "") +
                if (ok) "" else " (some writes were refused)")
            // A refused write after an update would be the first sign that Android changed the rules.
            if (ok) health("writes", "ok", "the last fix was written completely (${now()})")
            else health("writes", "attention", "Android refused some of the corrected battery values at ${now()}")
        } catch (e: Throwable) {
            log("evaluate failed: $e")
        }
    }

    // ---- LE Audio auto-connect (second job) -----------------------------------------------------------

    private class BtProcess(val pid: String, val id: String, val ageMs: Long)

    /** The running Bluetooth process, or null if there is none or more than one (a user switch). */
    private fun bluetoothProcess(): BtProcess? {
        val found = ArrayList<String>()
        for (p in File("/proc").list() ?: return null) {
            if (p.isEmpty() || !p.all { it.isDigit() }) continue
            val name = try { File("/proc/$p/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) } catch (_: Throwable) { continue }
            if (name in BT_NAMES) { found += p; continue }
            // A renamed Bluetooth app (an update could rename it): a package-like name ending in
            // "bluetooth" that runs as Bluetooth's user id. The Bluetooth hardware service runs as the
            // same id, but its name is a file path, so the pattern does not match it.
            if (BT_NAME_PATTERN.matches(name)) {
                val uid = try { File("/proc/$p/status").readLines().firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() } catch (_: Throwable) { null }
                if (uid != null && uid % 100_000 == BT_APP_ID) found += p
            }
        }
        if (found.size != 1) return null
        val pid = found[0]
        val stat = try { File("/proc/$pid/stat").readText() } catch (_: Throwable) { return null }
        // Field 22 (start time, in clock ticks since boot) counted after the ")" that ends the name.
        val start = stat.substringAfterLast(')').trim().split(' ').getOrNull(19)?.toLongOrNull() ?: return null
        val ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
        return BtProcess(pid, "$pid:$start", SystemClock.elapsedRealtime() - start * 1000 / ticks)
    }

    /** The output of a bounded command and why it ended: "done", "cap" or "deadline". */
    private class Bounded(val text: String, val ended: String)

    /**
     * Runs a command with a total deadline and a size cap, reading its output WHILE it writes. At the cap
     * the reader closes the pipe, so the command gets an error instead of waiting on a full pipe; at the
     * deadline the pipe is closed and the command is stopped.
     */
    private fun runBounded(cmd: List<String>, capBytes: Int, deadlineMs: Long): Bounded {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        val capped = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread({
            try {
                p.inputStream.use { s ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (out.size() + n > capBytes) { capped.set(true); break }
                        out.write(buf, 0, n)
                    }
                }
            } catch (_: Throwable) {}
        }, "budsfix-reader").apply { isDaemon = true; start() }
        val finished = p.waitFor(deadlineMs, TimeUnit.MILLISECONDS)
        if (!finished || capped.get()) {
            try { p.inputStream.close() } catch (_: Throwable) {}
            p.destroyForcibly()
        }
        reader.join(2_000)
        val ended = when { capped.get() -> "cap"; !finished -> "deadline"; else -> "done" }
        return Bounded(synchronized(out) { out.toString(Charsets.UTF_8.name()) }, ended)
    }

    /**
     * The buds' latest LE Audio connection state as Bluetooth's own log reports it ("CONNECTING_AUTOCONNECT"
     * means on the auto-connect list), "NONE" if the log has no state change for them, or null if the
     * log could not be read. Only lines of the given Bluetooth process are read.
     */
    private fun nativeLeState(bt: BtProcess, d: BluetoothDevice): String? {
        val tail = d.address.takeLast(5).lowercase(Locale.US)
        val r = try {
            runBounded(listOf("/system/bin/logcat", "-d", "-b", "main,system", "--pid=${bt.pid}",
                "-e", "SetConnectionState: [0-9a-fx:]*$tail,"), LOG_CAP_BYTES, LOG_DEADLINE_MS)
        } catch (_: Throwable) { null } ?: return null
        if (r.ended != "done") return null // cut off: the latest line may be missing
        val text = r.text
        val last = text.lineSequence().lastOrNull { it.contains("SetConnectionState:") && it.contains(" --> ") } ?: return "NONE"
        return last.substringAfter(" --> ").trim().substringBefore(' ')
    }

    private fun isArmedOrConnected(state: String?) =
        state == "CONNECTING_AUTOCONNECT" || state == "CONNECTED" || state == "CONNECTED_AUTOCONNECT_GETTING_READY"

    /** Once per Bluetooth start: make sure the Pixel Buds are on LE Audio's auto-connect list (see the top). */
    private fun maybeRearm() {
        if (rearmBusy) return
        val bt = bluetoothProcess()
        if (bt == null) {
            // Two processes during a user switch are normal for a moment; only a lasting absence is a problem.
            if (++missingBtProcess >= 3) health("bt-process", "attention", "could not find Bluetooth's process, so the LE Audio " +
                "step cannot tell when Bluetooth starts")
            return
        }
        missingBtProcess = 0
        health("bt-process", "ok", "found Bluetooth's process")
        val done = try { REARM_FILE.readText().trim() } catch (_: Throwable) { "" }
        if (done == bt.id) return
        if (rearmFor != bt.id) { rearmFor = bt.id; rearmTries = 0 }
        if (rearmTries >= 2) return
        if (bt.ageMs < BT_SETTLE_MS) {
            worker.postDelayed(::maybeRearm, BT_SETTLE_MS - bt.ageMs + 1_000)
            return
        }
        val buds = watched.values.map { it.device }.filter { isPixelBuds(it) }
        if (buds.isEmpty()) return
        val profiles = try {
            call { BluetoothAdapter::class.java.getMethod("getSupportedProfiles").invoke(adapter) as List<*> }
        } catch (_: Throwable) { emptyList<Any>() }
        if (profiles.isNotEmpty() && LE_AUDIO !in profiles) {
            health("le-audio", "info", "LE Audio is not in use on this device, so there is no LE Audio auto-connect list to look after")
            log("LE Audio is not in use on this device; nothing to put back on its auto-connect list")
            saveRearm(bt.id)
            testDone("LE Audio is not in use here")
            return
        }
        val ctx = context
        if (ctx == null) {
            health("le-audio", "attention", "the LE Audio step cannot run on this version, because no system context could be created")
            saveRearm(bt.id)
            testDone("no system context")
            return
        }
        rearmTries++
        rearmBusy = true
        val token = Any()
        rearmToken = token
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                worker.post {
                    // An answer that came after its wait ran out is not used.
                    if (!rearmBusy || rearmToken !== token) { closeProxy(proxy); return@post }
                    worker.removeCallbacksAndMessages(token)
                    rearm(bt, proxy, buds)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val asked = try { call { adapter.getProfileProxy(ctx, listener, LE_AUDIO) } } catch (e: Throwable) { log("LE Audio request failed: $e"); false }
        if (!asked) {
            rearmBusy = false
            rearmToken = null
            health("le-audio", "info", "LE Audio is not available on this device")
            log("LE Audio is not available on this device; nothing to put back on its auto-connect list")
            saveRearm(bt.id)
            testDone("LE Audio is not available here")
            return
        }
        worker.postAtTime({
            rearmBusy = false
            rearmToken = null
            log("LE Audio did not answer within ${PROXY_WAIT_MS / 1000} s" + if (rearmTries < 2) "; trying again at the next check" else "; not trying again until Bluetooth restarts")
            if (rearmTries >= 2) {
                health("le-audio", "attention", "LE Audio did not answer after Bluetooth started, so the Pixel Buds' auto-connect could not be checked")
                saveRearm(bt.id)
                testDone("LE Audio did not answer")
            }
        }, token, SystemClock.uptimeMillis() + PROXY_WAIT_MS)
    }

    private fun rearm(bt: BtProcess, proxy: BluetoothProfile, buds: List<BluetoothDevice>) {
        var keepProxy = false
        try {
            val c = proxy.javaClass
            val getPolicy = c.getMethod("getConnectionPolicy", BluetoothDevice::class.java)
            val setPolicy = c.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            for (d in buds) {
                val name = "${d.name} (${d.address.takeLast(5)})"
                val policy = call { getPolicy.invoke(proxy, d) as Int }
                val state = call { proxy.getConnectionState(d) }
                if (policy != POLICY_ALLOWED) {
                    report(name, "info", "LE Audio is switched off for them in their Bluetooth settings (setting $policy); left that way")
                    continue
                }
                if (state == STATE_CONNECTED || state == STATE_CONNECTING) {
                    report(name, "ok", "already ${if (state == STATE_CONNECTED) "connected" else "connecting"} over LE Audio; nothing changed")
                    continue
                }
                val logged = nativeLeState(bt, d)
                if (testForce) log("test: the log says $logged; re-applying anyway")
                if (!testForce && isArmedOrConnected(logged)) {
                    report(name, "ok", "already on LE Audio's auto-connect list; nothing changed")
                    continue
                }
                // Not on the list (or the log could not tell): re-apply the allowed setting.
                val why = when (logged) {
                    null -> "the system log could not be read"
                    "NONE" -> "the system log shows they were never put back on LE Audio's auto-connect list"
                    else -> "the system log shows them as $logged"
                }
                if (call { setPolicy.invoke(proxy, d, POLICY_ALLOWED) as Boolean }) {
                    report(name, "ok", "re-applied their allowed LE Audio setting ($why), so they are back on LE Audio's auto-connect list")
                    keepProxy = true
                    worker.postDelayed({ verify(bt, proxy, d, name, 1) }, VERIFY_AFTER_MS)
                } else {
                    report(name, "attention", "re-applying their LE Audio setting was refused ($why); they may not connect by themselves " +
                        "until you tap Connect once")
                }
            }
            saveRearm(bt.id)
        } catch (e: Throwable) {
            log("LE Audio step failed: $e${e.cause?.let { " / $it" } ?: ""}")
            health("le-audio", "attention", "the LE Audio step failed after Bluetooth started: $e")
        } finally {
            if (!keepProxy) closeProxy(proxy)
            rearmBusy = false
            rearmToken = null
            if (!keepProxy) testDone("nothing was re-applied")
        }
    }

    /** After re-applying: confirms the buds are connected or back on the auto-connect list. */
    private fun verify(bt: BtProcess, proxy: BluetoothProfile, d: BluetoothDevice, name: String, attempt: Int) {
        var again = false
        try {
            val state = try { call { proxy.getConnectionState(d) } } catch (_: Throwable) { -1 }
            if (state == STATE_CONNECTED) { report(name, "ok", "re-applied their LE Audio setting; confirmed connected over LE Audio"); return }
            val logged = nativeLeState(bt, d)
            when {
                isArmedOrConnected(logged) -> report(name, "ok", "re-applied their LE Audio setting; confirmed back on LE Audio's auto-connect list")
                logged == "CONNECTING_BY_USER" && attempt < 2 -> again = true
                logged == "DISCONNECTED" || logged == "CONNECTING_BY_USER" ->
                    report(name, "attention", "after re-applying their LE Audio setting, LE Audio did not keep them on its auto-connect list " +
                        "(log: $logged); they may not connect by themselves until you tap Connect once")
                else -> report(name, "info", "re-applied their LE Audio setting; the system log could not confirm the result")
            }
        } finally {
            if (again) worker.postDelayed({ verify(bt, proxy, d, name, attempt + 1) }, 30_000L)
            else {
                closeProxy(proxy)
                testDone("the result was checked")
            }
        }
    }

    private fun report(name: String, level: String, text: String) {
        log("LE Audio after a Bluetooth start: $name: $text")
        health("le-audio", level, "LE Audio after the Bluetooth start at ${now()}: $name: $text")
    }

    private fun closeProxy(proxy: BluetoothProfile) {
        try { call { adapter.closeProfileProxy(LE_AUDIO, proxy) } } catch (_: Throwable) {}
    }

    /** Ends a "test-rearm-force" run once the step has nothing more to do. */
    private fun testDone(why: String) {
        if (!testForce) return
        log("test finished: $why")
        System.exit(0)
    }

    private fun saveRearm(id: String) {
        try { REARM_FILE.writeText("$id\n") } catch (e: Throwable) { log("could not save the LE Audio step's state: $e") }
    }

    /** Read-only test, run by hand: what the LE Audio step would see right now. Changes nothing. */
    private fun testLeState() {
        Looper.prepareMainLooper()
        try { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null) } catch (_: Throwable) {}
        println("adapter: way ${shellAdapter()}")
        val bt = bluetoothProcess()
        println("Bluetooth process: ${bt?.let { "pid ${it.pid}, id ${it.id}, running ${it.ageMs / 1000} s" } ?: "not found"}")
        for (d in adapter.bondedDevices) if (isPixelBuds(d)) {
            println("${d.name} (${d.address.takeLast(5)}): connected=${connected(d)}, " +
                "LE Audio state in the log: ${bt?.let { nativeLeState(it, d) } ?: "no process"}")
        }
        System.out.flush()
    }

    /** Bounded test of the log reader, run by hand: a normal read, then one that hits the size cap. */
    private fun testLogReader() {
        fun show(label: String, cmd: List<String>, cap: Int, deadline: Long) {
            val t = SystemClock.uptimeMillis()
            val r = runBounded(cmd, cap, deadline)
            println("$label: ended by ${r.ended} after ${SystemClock.uptimeMillis() - t} ms with ${r.text.length} chars")
        }
        show("normal read (expect done)", listOf("/system/bin/logcat", "-d", "-b", "main", "-t", "5"), LOG_CAP_BYTES, LOG_DEADLINE_MS)
        show("big read with a 4 KB cap (expect cap)", listOf("/system/bin/logcat", "-d", "-b", "main,system"), 4096, LOG_DEADLINE_MS)
        show("endless read with a 2 s deadline (expect deadline)", listOf("/system/bin/logcat", "-b", "main", "-T", "1"), 1024 * 1024, 2_000)
        System.out.flush()
    }
}
