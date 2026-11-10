package local.budsfix

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

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
 * latest real levels and charging states, which the earbuds themselves reported shortly before
 * The reading counts as current if it arrived within MAX_AGE_MS, or if the earbuds have stayed
 * connected ever since (while connected, every change is reported). Otherwise it leaves the values
 * alone. Values that were already stored when the helper started are never used: their age is
 * unknown. The new case value is kept.
 * Every other change, real or unknown, passes through untouched, so any real report from the
 * earbuds always wins at once. It never invents a number, holds no wake lock and changes no setting.
 *
 * Second job, LE Audio auto-connect for the Pixel Buds: on a multi-user device, Bluetooth first starts
 * for the system user at boot. If that user's LE Audio setting for the buds is "forbidden", it takes
 * them off LE Audio's auto-connect list and saves that. When Bluetooth then starts for the person's
 * own user, its step that puts allowed devices back on the list can run before the buds are loaded
 * (seen on the Googlebook: 2 ms too early), so they are never put back and do not auto-connect. Once
 * per Bluetooth start, when Bluetooth has run for BT_SETTLE_MS, this helper re-applies the buds' LE
 * Audio setting in the current user, but only if it already is "allowed" (100) and LE Audio is not
 * connected or connecting. That is the same call as the LE Audio switch in Settings: it puts the buds
 * back on the auto-connect list and asks LE Audio to connect them. If they are in the case, the
 * request times out after 30 s and they stay on the auto-connect list. Classic audio is not touched.
 */
object Helper {
    // BluetoothDevice metadata keys (Android's public constants).
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
    /** The Bluetooth start (process id and start time) the step was last done for. */
    private val REARM_FILE = java.io.File("/data/local/tmp/budsfix/le-audio-rearm.txt")

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
    private lateinit var context: Context
    private lateinit var listenerClass: Class<*>
    private var rearmBusy = false
    private var rearmToken: Any? = null
    private var rearmFor = ""
    private var rearmTries = 0
    private lateinit var worker: Handler
    private val watched = mutableMapOf<String, Watched>()
    @Volatile private var callStartedAt = 0L

    private fun log(msg: String) {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        println("$t $msg")
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run()
        } catch (e: Throwable) {
            // Exit at once: Android's own crash handler can stall in an app_process program.
            log("stopping after an error: $e${e.cause?.let { " / $it" } ?: ""}")
            System.exit(1)
        }
    }

    private fun run() {
        Looper.prepareMainLooper()
        Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null)
        adapter = shellAdapter()
        listenerClass = Class.forName("android.bluetooth.BluetoothAdapter\$OnMetadataChangedListener")
        val thread = HandlerThread("budsfix").apply { start() }
        worker = Handler(thread.looper)
        startWatchdog()
        log("helper started (pid ${android.os.Process.myPid()}, Android ${android.os.Build.VERSION.RELEASE})")
        worker.post(::rescan)
        Looper.loop()
    }

    /** A Bluetooth adapter object that presents the adb shell user's identity. */
    private fun shellAdapter(): BluetoothAdapter {
        val shell = AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
        context = ShellContext(systemContext(), shell)
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "bluetooth_manager") as IBinder? ?: error("the Bluetooth manager service is not running")
        val mgrClass = Class.forName("android.bluetooth.IBluetoothManager")
        val mgr = Class.forName("android.bluetooth.IBluetoothManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val tries = listOf<() -> Any?>(
            { BluetoothAdapter::class.java.getDeclaredConstructor(mgrClass, Context::class.java, AttributionSource::class.java)
                .apply { isAccessible = true }.newInstance(mgr, context, shell) },
            { BluetoothAdapter::class.java.getDeclaredConstructor(mgrClass, AttributionSource::class.java)
                .apply { isAccessible = true }.newInstance(mgr, shell) },
            { BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", AttributionSource::class.java)
                .apply { isAccessible = true }.invoke(null, shell) },
            { BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", Context::class.java)
                .apply { isAccessible = true }.invoke(null, context) },
        )
        for (t in tries) {
            val a = try { t() } catch (_: Throwable) { null }
            if (a is BluetoothAdapter) return a
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

    /** Finds paired Fast Pair true wireless headsets and listens to each one. Repeats every 2 minutes. */
    private fun rescan() {
        try {
            if (call { adapter.state } != BluetoothAdapter.STATE_ON) {
                if (watched.isNotEmpty()) {
                    // Bluetooth went off: its listeners are gone. A fresh helper registers again.
                    log("Bluetooth is off; exiting so a fresh helper starts when it is back on")
                    System.exit(3)
                }
            } else {
                val bonded = call { adapter.bondedDevices } ?: emptySet()
                for (d in bonded) {
                    if (watched.containsKey(d.address)) continue
                    if (get(d, UNTETHERED) != "true") continue
                    watch(d)
                }
                for (w in watched.values) if (w.connectedSinceReal && !connected(w.device)) w.connectedSinceReal = false
                val gone = watched.keys - bonded.map { it.address }.toSet()
                for (a in gone) { watched.remove(a); log("$a is no longer paired") }
                maybeRearm()
            }
        } catch (e: Throwable) {
            log("rescan failed: $e")
        }
        worker.postDelayed(::rescan, RESCAN_MS)
    }

    /**
     * The running Bluetooth process as "pid:start time" plus its age in ms, or null if there is none
     * or more than one (a user switch in progress).
     */
    private fun bluetoothProcess(): Pair<String, Long>? {
        val found = ArrayList<String>()
        for (p in java.io.File("/proc").list() ?: return null) {
            if (p.isEmpty() || !p.all { it.isDigit() }) continue
            val name = try { java.io.File("/proc/$p/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) } catch (_: Throwable) { continue }
            if (name == "com.google.android.bluetooth" || name == "com.android.bluetooth") found += p
        }
        if (found.size != 1) return null
        val pid = found[0]
        val stat = try { java.io.File("/proc/$pid/stat").readText() } catch (_: Throwable) { return null }
        // Field 22 (start time, in clock ticks since boot) counted after the ")" that ends the name.
        val start = stat.substringAfterLast(')').trim().split(' ').getOrNull(19)?.toLongOrNull() ?: return null
        val ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
        val age = SystemClock.elapsedRealtime() - start * 1000 / ticks
        return "$pid:$start" to age
    }

    private fun isPixelBuds(d: BluetoothDevice): Boolean =
        (d.name ?: "").contains("Pixel Buds") || (get(d, 1) ?: "").contains("Pixel Buds") // 1 = model name

    /** Once per Bluetooth start: put the Pixel Buds back on LE Audio's auto-connect list (see the top). */
    private fun maybeRearm() {
        if (rearmBusy) return
        val (id, age) = bluetoothProcess() ?: return
        val done = try { REARM_FILE.readText().trim() } catch (_: Throwable) { "" }
        if (done == id) return
        if (rearmFor != id) { rearmFor = id; rearmTries = 0 }
        if (rearmTries >= 2) return
        if (age < BT_SETTLE_MS) {
            worker.postDelayed(::maybeRearm, BT_SETTLE_MS - age + 1_000)
            return
        }
        val buds = watched.values.map { it.device }.filter { isPixelBuds(it) }
        if (buds.isEmpty()) return
        val profiles = try {
            call { BluetoothAdapter::class.java.getMethod("getSupportedProfiles").invoke(adapter) as List<*> }
        } catch (_: Throwable) { emptyList<Any>() }
        if (profiles.isNotEmpty() && LE_AUDIO !in profiles) {
            log("LE Audio is not in use on this device; nothing to put back on its auto-connect list")
            saveRearm(id)
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
                    rearm(id, proxy, buds)
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val asked = try { call { adapter.getProfileProxy(context, listener, LE_AUDIO) } } catch (e: Throwable) { log("LE Audio request failed: $e"); false }
        if (!asked) {
            rearmBusy = false
            log("LE Audio is not available on this device; nothing to put back on its auto-connect list")
            saveRearm(id)
            return
        }
        worker.postAtTime({
            rearmBusy = false
            rearmToken = null
            log("LE Audio did not answer within ${PROXY_WAIT_MS / 1000} s" + if (rearmTries < 2) "; trying again at the next check" else "; not trying again until Bluetooth restarts")
            if (rearmTries >= 2) saveRearm(id)
        }, token, SystemClock.uptimeMillis() + PROXY_WAIT_MS)
    }

    private fun rearm(id: String, proxy: BluetoothProfile, buds: List<BluetoothDevice>) {
        try {
            val c = proxy.javaClass
            val getPolicy = c.getMethod("getConnectionPolicy", BluetoothDevice::class.java)
            val setPolicy = c.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            for (d in buds) {
                val policy = call { getPolicy.invoke(proxy, d) as Int }
                val state = call { proxy.getConnectionState(d) }
                val what = when {
                    policy != POLICY_ALLOWED -> "LE Audio is not allowed for them (setting $policy); nothing changed"
                    state == STATE_CONNECTED -> "already connected over LE Audio; nothing changed"
                    state == STATE_CONNECTING -> "already connecting over LE Audio; nothing changed"
                    call { setPolicy.invoke(proxy, d, POLICY_ALLOWED) as Boolean } ->
                        "re-applied their allowed LE Audio setting, so they are back on LE Audio's auto-connect list"
                    else -> "re-applying their LE Audio setting was refused"
                }
                log("LE Audio after a Bluetooth start: ${d.name} (${d.address.takeLast(5)}): $what")
            }
            saveRearm(id)
        } catch (e: Throwable) {
            log("LE Audio step failed: $e${e.cause?.let { " / $it" } ?: ""}")
        } finally {
            closeProxy(proxy)
            rearmBusy = false
            rearmToken = null
        }
    }

    private fun closeProxy(proxy: BluetoothProfile) {
        try { call { adapter.closeProfileProxy(LE_AUDIO, proxy) } } catch (_: Throwable) {}
    }

    private fun saveRearm(id: String) {
        try { REARM_FILE.writeText("$id\n") } catch (e: Throwable) { log("could not save the LE Audio step's state: $e") }
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
        } catch (e: Throwable) {
            log("evaluate failed: $e")
        }
    }
}
