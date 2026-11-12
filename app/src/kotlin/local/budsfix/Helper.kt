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
    /**
     * The latest real bud reading of each watched headset, with its time, so a restarted helper (after an
     * app update, a crash or Bluetooth turning off and on) still knows it. Only used within the same boot,
     * because the times are counted from the boot.
     */
    private var LAST_REAL_FILE = File("$DIR/last-real.txt")
    /**
     * Present only on devices where the owner wants it (on my Lenovo Tab P12): switch Wireless debugging back on
     * when Android switched it off because Wi-Fi dropped (for example when the router's address lease ran out).
     */
    private val KEEP_WD_FILE = File("$DIR/keep-wireless-debugging")

    // ---- Keeping Google's Fast Pair pop-up right while the buds are connected (see guard()) ----
    /**
     * Play services keeps a real bud level against the buds' "unknown" broadcasts for 120 s after the buds last
     * reported (FastPairConfig__battery_all_the_time_battery_info_expire_millis). While audio plays, the buds
     * broadcast "unknown" all the time, and sometimes while idle too (2026-11-12 01:44:32), so after 120 s without
     * a report the pop-up drops to "Case N%".
     * The knock: open the buds' LE Message Stream channel (the L2CAP channel Play services itself uses) for a
     * moment. The buds allow one LE stream per device, so Play services' stream closes; Play services re-opens it
     * by itself within about 7 s, and on that connect the buds send their battery to every connected device
     * (tested 2026-11-12 00:58). It never touches the Classic link, which competes with LE Audio for the radio.
     * On a device whose Play services uses the Classic (RFCOMM) form of the Message Stream, such as the Lenovo Tab P12
     * (Android 15, A2DP audio), the file KNOCK_RFCOMM_FILE selects an RFCOMM knock instead: same effect, Play
     * services' stream is knocked off and re-opened, and the buds report. Either way a knock only counts once Play
     * services has logged the buds' report.
     */
    private val MESSAGE_STREAM_UUID = java.util.UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
    /** Present (on the Lenovo Tab P12 only) to knock over RFCOMM, the form its Play services uses. */
    private val KNOCK_RFCOMM_FILE = File("$DIR/knock-rfcomm")
    /** How often the guard looks: often while it protects (audio playing or armed), less often otherwise. */
    private const val GUARD_ACTIVE_EVERY_MS = 4_000L
    private const val GUARD_IDLE_EVERY_MS = 10_000L
    /**
     * Knock when the buds' last report is this old. Google's limit is 120 s, and a knock took 0.3 to 1.5 s in
     * tests, so 110 s plus at most one 4 s check plus the knock still lands about 5 s before the limit. Knocking
     * this late lets more of the buds' own reports do the job.
     */
    private const val KNOCK_AFTER_MS = 90_000L
    /** After a knock, Play services' re-opened stream must bring a battery report within this time. */
    private const val KNOCK_VERIFY_MS = 20_000L
    /** After a flip that was not prevented, keep preventing for this long, even without audio from here. */
    private const val ARM_AFTER_FLIP_MS = 10 * 60_000L
    private const val KNOCK_CONNECT_LIMIT_MS = 5_000L

    // ---- Flanging fix (see quietClassic()) ----
    /**
     * Over LE Audio on my Googlebook, the sound sometimes flanged with left and right fading (2026-11-12).
     * Evidence: the Googlebook's own audio path was clean (no lost ISO events, no underruns), the links to the buds
     * timed out several times, and with the buds' Classic link to this device gone, ten minutes of video had no
     * flanging, no Classic activity and no timeouts. The Classic link was kept busy by the Pixel Buds app's
     * background connection. So while audio plays to the buds over LE Audio, if the Classic link is up and the
     * Pixel Buds app is only in the background (not on screen, not updating firmware), its background connection
     * is paused with a regular stop ("am stop-app", which keeps its scheduled jobs, alarms and notifications); the
     * buds then close the idle Classic link by themselves.
     * Why the stopped mark: the app's manifest receiver ClassicBTReceiver handles ACL_CONNECTED/DISCONNECTED and the
     * HFP and LE Audio connection changes. On ACL_DISCONNECTED it asks whether the buds are still connected by any
     * profile, LE Audio counts, and it reopens its RFCOMM channel at once (app 1.0.996096625, classes gpf and gil;
     * seen 2026-11-12 01:17:11). Android does not deliver such broadcasts to a package carrying the "stopped" mark
     * (the 00:41 test). The adb shell may set only that mark (PackageManagerService.setPackageStoppedState needs
     * CHANGE_COMPONENT_ENABLED_STATE; it does not cancel jobs, alarms or notifications, unlike a force-stop).
     * To keep the mark short, the Googlebook closes the idle Classic link itself right after the stop
     * (BluetoothDevice.disconnectAcl with transport BR/EDR only; LE Audio is untouched) instead of waiting about 4 s
     * for the buds to close it. The mark is cleared as soon as the link reads closed plus STOPPED_MARK_MARGIN_MS.
     * Opening the app, the buds' next connection change and Bluetooth turning on all reconnect it as usual.
     */
    private const val PIXEL_BUDS_APP = "com.google.android.apps.wearables.maestro.companion"
    /** "marked|user|wall ms" while the stopped mark is ours, "cleared|..." afterwards. */
    private val STOPPED_MARK_FILE = File("$DIR/stopped-mark.txt")
    /** After the Classic link reads closed, the time Android needs to send its "link closed" broadcast. */
    private const val STOPPED_MARK_MARGIN_MS = 150L
    /** The longest the Classic link may take to close after disconnectAcl. */
    private const val CLASSIC_CLOSE_WAIT_MS = 2_000L
    /** The mark is never kept longer than this; a watchdog thread clears it if anything stalls. */
    private const val STOPPED_MARK_LIMIT_MS = 3_000L
    private const val QUIET_MIN_GAP_MS = 3 * 60_000L
    /** Present to switch the flanging fix off. */
    private val NO_QUIET_FILE = File("$DIR/no-classic-quiet")
    /** Play services' LE stream channel to the buds, saved as "address|channel" for when the log has rolled over. */
    private val PSM_FILE = File("$DIR/stream-channel.txt")
    /** Present to switch the guard off (nothing else is affected). */
    private val NO_GUARD_FILE = File("$DIR/no-popup-guard")
    /** Evidence of each "buds unknown" event: the Fast Pair pop-up's text and Play services' battery log lines. */
    private val EVIDENCE_DIR = File("$DIR/evidence")
    private const val EVIDENCE_CAP_BYTES = 30L * 1024 * 1024
    private const val EVIDENCE_MIN_GAP_MS = 60_000L
    /** A connection check older than this, from before a restart, does not count as "connected since". */
    private const val RESTORE_GAP_MS = 60_000L
    /** Test run by hand ("test-rearm-force"): own state files, always re-applies, exits after confirming. */
    private var testForce = false

    private class Reading(val left: String, val leftCharging: String, val right: String, val rightCharging: String, val at: Long)

    private class Watched(val device: BluetoothDevice, val listener: Any) {
        /** The latest real bud reading Fast Pair stored while this helper was watching. */
        var lastReal: Reading? = null
        /** True while the earbuds have stayed connected at every check since lastReal arrived. */
        var connectedSinceReal = false
        /** When the connection was last checked and found connected (elapsed time), for the saved state. */
        var checkedAt = 0L
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
    private var lastEvidenceAt = 0L
    @Volatile private var lastReportWall = 0L       // wall clock ms of the buds' latest known battery report
    private var armedUntilWall = 0L                 // prevention also active until then (after a flip)
    @Volatile private var knocking = false
    private val knockTimes = ArrayDeque<Long>()     // wall clock ms of recent knocks, for the self-check
    /** Play services' LE stream channel (PSM) to the buds, learned from the Bluetooth log, per Bluetooth start. */
    private var streamPsm = 0
    private var streamPsmFor = ""
    @Volatile private var rfcommPort = 0           // Message Stream RFCOMM channel learned from a knock (RFCOMM devices)
    private var lastQuietAt = 0L
    private var quietCheckAfter = 0L               // wall clock: no new look before then (app in use, or not the cause)
    @Volatile private var knockBusyUntil = 0L      // wall clock: a knock is waiting for Play services' report
    @Volatile private var quietBusy = false         // a stop is in progress
    private val markLock = Object()
    @Volatile private var markedUser: Int? = null   // user in which we set the stopped mark, until it is cleared
    private val uidCache = HashMap<String, Int>()   // package name -> uid in the current user
    private var wdLastOnAt = 0L                     // wall clock time Wireless debugging was last seen on
    private val wdSwitchOns = ArrayDeque<Long>()    // recent automatic switch-ons, for the limit
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
            if (args.firstOrNull() == "test-audio") { testAudio(); System.exit(0) }
            if (args.firstOrNull() == "test-mark") {
                Looper.prepareMainLooper()
                val uid = uidOf(PIXEL_BUDS_APP)
                println("Pixel Buds app uid: $uid; stopped mark in user ${uid?.div(100_000)}: ${uid?.let { isStopped(it / 100_000) }}")
                fun has(c: Class<*>, n: String, vararg t: Class<*>?) = try { c.getMethod(n, *t); true } catch (e: Throwable) { false }
                println("setPackageStoppedState: ${has(ipm().javaClass, "setPackageStoppedState", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)}; " +
                    "stopAppForUser: ${has(Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!.javaClass, "stopAppForUser", String::class.java, Int::class.javaPrimitiveType)}; " +
                    "disconnectAcl: ${has(BluetoothDevice::class.java, "disconnectAcl", Int::class.javaPrimitiveType)}")
                System.out.flush(); System.exit(0)
            }
            if (args.firstOrNull() == "test-knock") { HEALTH_FILE = File("$DIR/test-health.txt"); testKnock(); System.exit(0) }
            if (args.firstOrNull() == "test-rearm-force") {
                testForce = true
                REARM_FILE = File("$DIR/test-le-audio-rearm.txt")
                HEALTH_FILE = File("$DIR/test-health.txt")
                VERSIONS_FILE = File("$DIR/test-versions.txt")
                LAST_REAL_FILE = File("$DIR/test-last-real.txt")
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
        healStoppedMark()
        checkVersions()
        checkPermission()
        worker.post(::rescan)
        worker.postDelayed(::guard, GUARD_IDLE_EVERY_MS)
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
                var changed = false
                for (w in watched.values) if (w.connectedSinceReal) {
                    if (connected(w.device)) w.checkedAt = SystemClock.elapsedRealtime() else w.connectedSinceReal = false
                    changed = true
                }
                if (changed) saveReadings()
                val gone = watched.keys - bonded.map { it.address }.toSet()
                for (a in gone) { watched.remove(a); healthRemove("buds-$a"); log("$a is no longer paired") }
                maybeRearm()
                keepWirelessDebugging()
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
        restoreReading(w)
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
                    w.checkedAt = SystemClock.elapsedRealtime()
                    saveReadings()
                    lastReportWall = maxOf(lastReportWall, System.currentTimeMillis())
                }
                return
            }
            val badPattern = level(l) == -1 && level(r) == -1 && lc == "true" && rc == "true" && level(c) in 0..100
            if (!badPattern) return // genuinely unknown or partial: leave it alone
            recordEvidence(d, "Fast Pair stored the buds as unknown with the case at $c%")
            // Safety net: the guard did not prevent this one (for example audio from another device). Make the
            // buds report now, and keep preventing for a while.
            if (isPixelBuds(d) && connected(d) && !NO_GUARD_FILE.exists()) {
                val nowWall = System.currentTimeMillis()
                val sinceReport = if (lastReportWall > 0) nowWall - lastReportWall else -1L
                // If this happens although the guard was protecting and the buds reported recently, Google has
                // probably shortened its 120 s limit in an update: say so, so it gets looked at.
                if (!NO_GUARD_FILE.exists() && sinceReport in 0 until 112_000) {
                    health("popup-limit", "attention", "Google's pop-up dropped the buds only ${sinceReport / 1000} s after their " +
                        "last report (${now()}); either Play services did not receive that report, or an update shortened its 2 minute limit")
                }
                armedUntilWall = nowWall + ARM_AFTER_FLIP_MS
                knock(d, "Google's pop-up dropped the buds")
            }
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

    // ---- Guard for Google's pop-up (see MESSAGE_STREAM_UUID) ----------------------------------------------

    /** Runs on the worker every few seconds: the flanging fix during LE Audio playback, and the pop-up guard while the buds are connected here and out of the case. */
    private fun guard() {
        var protecting = false
        try {
            run {
                // A stopped mark whose clearing failed: try again at every check until it works.
                if (markedUser != null && !quietBusy) clearStoppedMark("retry after a failed clear")
                val w = watched.values.firstOrNull { isPixelBuds(it.device) }
                val real = w?.lastReal
                // Only with the buds connected here and out of the case (both last really reported "not charging").
                if (w != null && real != null && real.leftCharging == "false" && real.rightCharging == "false" && connected(w.device)) {
                    val now = System.currentTimeMillis()
                    val route = audioRoute(w.device)
                    val playing = route != null
                    if (route == "le" && !NO_QUIET_FILE.exists()) quietClassic(w.device)
                    // The buds broadcast "unknown" while idle too (2026-11-12 01:44:32, no audio since 01:39:44), so
                    // the guard protects whenever they are connected here and out of the case; it still knocks only
                    // when their last report is KNOCK_AFTER_MS old (they often report by themselves).
                    if (!NO_GUARD_FILE.exists()) {
                        protecting = true
                        if (lastReportWall == 0L) lastReportWall = now - KNOCK_AFTER_MS / 2 // unknown: give it a short grace
                        if (now - lastReportWall >= KNOCK_AFTER_MS) {
                            // Maybe the buds reported by themselves (or the other device knocked): Play services logs it.
                            latestReportFromLog()?.let { if (it > lastReportWall) lastReportWall = it }
                            if (now - lastReportWall >= KNOCK_AFTER_MS)
                                knock(w.device, if (playing) "audio is playing" else "buds connected, no audio")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            log("pop-up guard check failed: $e")
        }
        worker.postDelayed(::guard, if (protecting) GUARD_ACTIVE_EVERY_MS else GUARD_IDLE_EVERY_MS)
    }

    /** True while music plays on this device and the buds are its active LE Audio or A2DP device. */
    private fun audioToBuds(d: BluetoothDevice): Boolean = audioRoute(d) != null

    /** "le" or "a2dp" while music plays on this device to the buds over that profile, otherwise null. */
    private fun audioRoute(d: BluetoothDevice): String? {
        val music = try {
            val am = context?.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager?
            call { am?.isMusicActive } ?: false
        } catch (_: Throwable) { false }
        if (!music) return null
        val getActive = try { BluetoothAdapter::class.java.getMethod("getActiveDevices", Int::class.javaPrimitiveType) } catch (_: Throwable) { return null }
        for ((profile, name) in listOf(LE_AUDIO to "le", 2 /* A2DP */ to "a2dp")) {
            val list = try { call { getActive.invoke(adapter, profile) as List<*>? } } catch (_: Throwable) { null } ?: continue
            if (list.any { (it as? BluetoothDevice)?.address == d.address }) return name
        }
        return null
    }

    /** The uid of a package in the current user, from the package manager (cached), or null if not installed. */
    private fun uidOf(pkg: String): Int? {
        uidCache[pkg]?.let { return it }
        val user = runBounded(listOf("/system/bin/cmd", "activity", "get-current-user"), 4096, 5_000).text.trim().toIntOrNull() ?: return null
        val out = runBounded(listOf("/system/bin/pm", "list", "packages", "-U", "--user", "$user", pkg), 64 * 1024, 10_000).text
        val uid = out.lines().firstOrNull { it.startsWith("package:$pkg ") }?.substringAfter("uid:")?.trim()?.substringBefore(',')?.toIntOrNull()
            ?: return null
        if (uid / 100_000 != user) return null
        uidCache[pkg] = uid
        return uid
    }

    /**
     * The flanging fix (see PIXEL_BUDS_APP). Called while audio plays to the buds over LE Audio. Leaves the Pixel
     * Buds app alone while it is on screen (TOP, BOUND_TOP, TOP_SLEEPING), runs a foreground service such as a
     * firmware update (FOREGROUND_SERVICE, BOUND_FOREGROUND_SERVICE), or when the Classic link is not up.
     */
    private fun quietClassic(d: BluetoothDevice) {
        val now = System.currentTimeMillis()
        if (now - lastQuietAt < QUIET_MIN_GAP_MS || now < quietCheckAfter) return
        val classicUp = try {
            call { BluetoothDevice::class.java.getMethod("isConnected", Int::class.javaPrimitiveType).invoke(d, 1 /* BR/EDR */) as Boolean }
        } catch (_: Throwable) { return }
        if (!classicUp) return
        val uid = uidOf(PIXEL_BUDS_APP) ?: return
        val state = runBounded(listOf("/system/bin/cmd", "activity", "get-uid-state", "$uid"), 4096, 5_000).text.trim()
        val code = state.substringBefore(' ').toIntOrNull() ?: return
        if (code <= 5 || code == 12) { quietCheckAfter = now + 15_000; return } // in use: on screen or updating
        // Only if the app really holds a connection to the buds (its newest RFCOMM line in the Bluetooth log says
        // connected). If the log no longer has a line, the Classic link being up with the app in the background is
        // the established cause (nothing else here opens Classic to the buds: Classic audio is off for them).
        val tail = d.address.takeLast(5).lowercase(Locale.US)
        val lr = runBounded(listOf("/system/bin/logcat", "-d", "-b", "main,system", "-e",
            "RFCOMM socket connections for device: [0-9a-fx:]*$tail, scn: [0-9]+, app_uid: $uid"), LOG_CAP_BYTES, LOG_DEADLINE_MS)
        if (lr.ended != "done") { quietCheckAfter = now + 15_000; return }
        val last = lr.text.lines().lastOrNull { it.contains("app_uid: $uid") }
        if (last != null && !last.contains("connected to RFCOMM")) { quietCheckAfter = now + 15_000; return }
        if (quietBusy) return
        val user = uid / 100_000
        if (isStopped(user) != false) {
            // Already stopped by someone else, or unreadable: leave the app alone.
            quietCheckAfter = now + 10 * 60_000L
            return
        }
        lastQuietAt = now
        quietBusy = true
        Thread({
            try { markAndStop(d, user, state) } catch (e: Throwable) { log("flanging fix failed: $e") } finally { quietBusy = false }
        }, "budsfix-quiet").apply { isDaemon = true; start() }
    }

    private fun classicUp(d: BluetoothDevice): Boolean? = try {
        call { BluetoothDevice::class.java.getMethod("isConnected", Int::class.javaPrimitiveType).invoke(d, 1 /* BR/EDR */) as Boolean }
    } catch (_: Throwable) { null }

    private fun ipm(): Any = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!

    /** True if the Pixel Buds app carries Android's stopped mark in [user], null if unreadable. */
    private fun isStopped(user: Int): Boolean? = try {
        val pm = ipm()
        pm.javaClass.getMethod("isPackageStoppedForUser", String::class.java, Int::class.javaPrimitiveType).invoke(pm, PIXEL_BUDS_APP, user) as Boolean
    } catch (e: Throwable) { log("could not read the Pixel Buds app's stopped mark: ${e.cause ?: e}"); null }

    /** Sets or clears only the stopped mark (no kill, no other effect); true if the mark then reads as wanted. */
    private fun setStopped(user: Int, stopped: Boolean): Boolean = try {
        val pm = ipm()
        pm.javaClass.getMethod("setPackageStoppedState", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(pm, PIXEL_BUDS_APP, stopped, user)
        isStopped(user) == stopped
    } catch (e: Throwable) { log("could not ${if (stopped) "set" else "clear"} the Pixel Buds app's stopped mark: ${e.cause ?: e}"); false }

    /** Clears our stopped mark if it is still set. Safe to call twice. */
    private fun clearStoppedMark(why: String) {
        synchronized(markLock) {
            val user = markedUser ?: return
            val ok = isStopped(user) == false || setStopped(user, false)
            if (ok) {
                markedUser = null
                try { STOPPED_MARK_FILE.writeText("cleared|$user|${System.currentTimeMillis()}\n") } catch (_: Throwable) {}
                if (why != "done") log("flanging fix: cleared the Pixel Buds app's stopped mark ($why)")
            } else {
                health("classic-quiet", "attention", "could not clear the Pixel Buds app's stopped mark ($why); the helper tries again " +
                    "at its next check (${now()})")
            }
        }
    }

    /** At start: if an earlier run ended while our mark was set, clear it. */
    private fun healStoppedMark() {
        val parts = try { STOPPED_MARK_FILE.readText().trim().split('|') } catch (_: Throwable) { return }
        if (parts.size < 2 || parts[0] != "marked") return
        val user = parts[1].toIntOrNull() ?: return
        synchronized(markLock) { markedUser = user }
        clearStoppedMark("an earlier run ended while it was set")
    }

    /** Stops the app with Android's regular stop: kills its processes, keeps its jobs, alarms and notifications. */
    private fun regularStop(user: Int): String = try {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        am.javaClass.getMethod("stopAppForUser", String::class.java, Int::class.javaPrimitiveType).invoke(am, PIXEL_BUDS_APP, user)
        "done"
    } catch (e: Throwable) {
        val r = runBounded(listOf("/system/bin/am", "stop-app", "--user", "$user", PIXEL_BUDS_APP), 4096, 10_000)
        if (r.ended == "done") "done (am stop-app)" else "ended by ${r.ended}"
    }

    /**
     * The flanging fix itself, on its own thread: stopped mark, regular stop, the Googlebook closes the idle Classic
     * link (BR/EDR only), mark cleared once the link reads closed plus STOPPED_MARK_MARGIN_MS. A watchdog clears the
     * mark after STOPPED_MARK_LIMIT_MS whatever happens.
     */
    private fun markAndStop(d: BluetoothDevice, user: Int, appState: String) {
        val t0 = SystemClock.uptimeMillis()
        try { STOPPED_MARK_FILE.writeText("marked|$user|${System.currentTimeMillis()}\n") } catch (_: Throwable) {}
        Thread({
            SystemClock.sleep(STOPPED_MARK_LIMIT_MS)
            if (markedUser != null && SystemClock.uptimeMillis() - t0 >= STOPPED_MARK_LIMIT_MS) clearStoppedMark("time limit of ${STOPPED_MARK_LIMIT_MS} ms")
        }, "budsfix-quiet-watchdog").apply { isDaemon = true; start() }
        var closedAt = -1L
        var stopped = ""
        var disc = ""
        try {
            if (!setStopped(user, true)) {
                if (isStopped(user) == true) synchronized(markLock) { markedUser = user }
                log("flanging fix: could not set the Pixel Buds app's stopped mark, so I did not stop the app")
                return
            }
            synchronized(markLock) { markedUser = user }
            stopped = regularStop(user)
            // The Bluetooth service on this build returns Integer.MAX_VALUE even when it does close the link
            // (seen 2026-11-12 02:02:49: "disconnect_acl ... BR_EDR", link down 193 ms later), so the code is only
            // logged; what counts is whether the link really reads closed.
            disc = try {
                val code = BluetoothDevice::class.java.getMethod("disconnectAcl", Int::class.javaPrimitiveType).invoke(d, 1 /* BR/EDR only */) as Int
                "requested (code $code)"
            } catch (e: Throwable) { "failed: ${e.cause ?: e}" }
            if (disc.startsWith("requested")) {
                while (SystemClock.uptimeMillis() - t0 < CLASSIC_CLOSE_WAIT_MS) {
                    if (classicUp(d) == false) { closedAt = SystemClock.uptimeMillis() - t0; break }
                    SystemClock.sleep(5)
                }
                if (closedAt >= 0) SystemClock.sleep(STOPPED_MARK_MARGIN_MS)
            }
        } finally {
            clearStoppedMark("done")
        }
        val markMs = SystemClock.uptimeMillis() - t0
        log("flanging fix: audio plays over LE Audio while the Pixel Buds app held the Classic link in the background ($appState); " +
            "regular stop $stopped; Classic link close $disc, ${if (closedAt >= 0) "closed after $closedAt ms" else "not closed"}; " +
            "stopped mark set for $markMs ms")
        if (closedAt < 0) health("classic-quiet", "attention", "the Googlebook could not close the Classic link ($disc) at ${now()}")
        worker.postDelayed({
            val up = classicUp(d)
            log("flanging fix: 20 s later the Classic link is ${if (up == false) "still closed" else if (up == true) "open again" else "unknown"}")
            if (up == false) health("classic-quiet", "ok", "kept the Classic link quiet during LE Audio playback (last at ${now()}, mark $markMs ms)")
            else if (up == true) {
                health("classic-quiet", "info", "the Classic link opened again after the flanging fix at ${now()}; next try in 10 minutes")
                quietCheckAfter = System.currentTimeMillis() + 10 * 60_000L
            }
        }, 20_000)
    }

    /**
     * Play services' LE stream channel to the buds, from the Bluetooth log line "L2CAP connection for device:
     * <buds>, channel: N, app_uid: <Play services>". Learned once per Bluetooth start; checked to be a dynamic LE
     * channel number (0x80 to 0xFF). Null if the log does not show it.
     */
    private fun streamPsmFor(d: BluetoothDevice): Int? {
        val bt = bluetoothProcess()?.id ?: return null
        if (streamPsmFor == bt && streamPsm > 0) return streamPsm
        val gms = uidOf("com.google.android.gms") ?: return null
        val tail = d.address.takeLast(5).lowercase(Locale.US)
        val r = runBounded(listOf("/system/bin/logcat", "-d", "-b", "main,system", "-e",
            "L2CAP connection for device: [0-9a-fx:]*$tail, channel: [0-9]+, app_uid: $gms"), LOG_CAP_BYTES, LOG_DEADLINE_MS)
        val fromLog = if (r.ended == "done")
            r.text.lines().mapNotNull { Regex("channel: ([0-9]+), app_uid: $gms").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                .lastOrNull()?.takeIf { it in 0x80..0xFF } else null
        val psm = fromLog ?: try {
            PSM_FILE.readText().trim().split('|').takeIf { it.size == 2 && it[0] == d.address }?.get(1)?.toIntOrNull()?.takeIf { it in 0x80..0xFF }
        } catch (_: Throwable) { null } ?: return null
        if (fromLog != null) try { PSM_FILE.writeText("${d.address}|$psm\n") } catch (_: Throwable) {}
        streamPsm = psm
        streamPsmFor = bt
        return psm
    }

    /**
     * Wall clock time of the newest "The device pass battery info to seeker" line from Play services (the buds'
     * battery report, whoever caused it) in the last 3 minutes, or null. Bounded read of the main log.
     */
    private fun latestReportFromLog(): Long? {
        val since = (System.currentTimeMillis() - 180_000) / 1000.0
        val r = try {
            runBounded(listOf("/system/bin/logcat", "-d", "-b", "main", "-v", "epoch", "-T", String.format(Locale.US, "%.3f", since),
                "-e", "pass battery info to seeker"), LOG_CAP_BYTES, LOG_DEADLINE_MS)
        } catch (_: Throwable) { return null }
        if (r.ended != "done") return null
        return r.text.lineSequence().mapNotNull { it.trim().substringBefore(' ').toDoubleOrNull() }
            .maxOrNull()?.let { (it * 1000).toLong() }
    }

    /**
     * The LE knock (see the top of this section): opens Play services' LE stream channel to the buds for a moment
     * and closes it. Play services re-opens its own stream within seconds, and the buds then report their battery
     * to every connected device. Checks afterwards that the report really arrived. One knock at a time, hard time
     * limits, and the channel is always closed. Never uses the Classic link.
     */
    private fun knock(d: BluetoothDevice, why: String) {
        if (knocking || System.currentTimeMillis() < knockBusyUntil) return
        val viaRfcomm = KNOCK_RFCOMM_FILE.exists()
        val psm = if (viaRfcomm) 0 else streamPsmFor(d)
        if (psm == null) {
            health("popup-guard", "attention", "could not find Play services' LE stream channel to the buds in the Bluetooth log, so " +
                "the pop-up guard could not act (${now()})")
            return
        }
        knocking = true
        val startedWall = System.currentTimeMillis()
        knockBusyUntil = startedWall + KNOCK_VERIFY_MS + 2_000
        val ageS = if (lastReportWall > 0) (startedWall - lastReportWall) / 1000 else -1
        Thread({
            var sock: android.bluetooth.BluetoothSocket? = null
            var opened = false
            var result: String
            val port = rfcommPort
            val what = if (!viaRfcomm) "LE channel $psm" else if (port > 0) "RFCOMM channel $port" else "the RFCOMM Message Stream"
            try {
                sock = if (!viaRfcomm) d.createInsecureL2capChannel(psm)
                    else if (port > 0) BluetoothDevice::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(d, port) as android.bluetooth.BluetoothSocket
                    else d.createRfcommSocketToServiceRecord(MESSAGE_STREAM_UUID)
                val s = sock
                var connectError: Throwable? = null
                val connector = Thread({ try { s.connect() } catch (e: Throwable) { connectError = e } }, "budsfix-knock-connect")
                connector.isDaemon = true
                connector.start()
                connector.join(KNOCK_CONNECT_LIMIT_MS)
                opened = s.isConnected
                result = if (opened) "opened $what" else "could not open $what (${connectError ?: "no answer within ${KNOCK_CONNECT_LIMIT_MS / 1000} s"})"
                if (opened) {
                    if (viaRfcomm && port == 0) rfcommPort = try {
                        android.bluetooth.BluetoothSocket::class.java.getDeclaredField("mPort").apply { isAccessible = true }.getInt(s)
                    } catch (_: Throwable) { 0 }
                    SystemClock.sleep(200)
                }
            } catch (e: Throwable) {
                result = "failed: $e"
            } finally {
                try { sock?.close() } catch (_: Throwable) {}
                knocking = false
            }
            val took = System.currentTimeMillis() - startedWall
            synchronized(knockTimes) {
                knockTimes.addLast(startedWall)
                while (knockTimes.isNotEmpty() && startedWall - knockTimes.first() > 3_600_000) knockTimes.removeFirst()
            }
            log("pop-up guard: knocked ($why, last report ${if (ageS >= 0) "$ageS s" else "unknown"} ago): $result, in $took ms")
            if (!opened) {
                if (viaRfcomm) rfcommPort = 0          // look the channel up again next time
                else { streamPsm = 0; streamPsmFor = "" }
                // An RFCOMM attempt that the buds refuse can still knock Play services' stream off (seen on the
                // tablet 2026-11-11), so it is judged by the report below, like a knock that opened.
                if (!viaRfcomm) {
                    knockBusyUntil = System.currentTimeMillis() + 15_000
                    health("popup-guard", "attention", "the last knock did not work ($result) at ${now()}")
                    return@Thread
                }
            }
            // Did Play services re-open its stream and get the buds' report?
            worker.postDelayed({
                val t = latestReportFromLog()
                if (t != null && t >= startedWall) {
                    if (t > lastReportWall) lastReportWall = t
                    log("pop-up guard: Play services got the buds' report ${(t - startedWall) / 100 / 10.0} s after the knock")
                    health("popup-guard", "ok", "kept Google's pop-up current; ${synchronized(knockTimes) { knockTimes.size }} knocks in the last hour")
                } else {
                    knockBusyUntil = 0L
                    if (viaRfcomm) rfcommPort = 0
                    log("pop-up guard: no report from the buds within ${KNOCK_VERIFY_MS / 1000} s of the knock")
                    health("popup-guard", "attention", "after the knock at ${now()}, Play services got no report from the buds within " +
                        "${KNOCK_VERIFY_MS / 1000} s")
                }
            }, KNOCK_VERIFY_MS)
        }, "budsfix-knock").apply { isDaemon = true; start() }
    }

    // ---- Evidence recorder (read only) ------------------------------------------------------------------

    /**
     * Saves what the owner would see and why, at a "buds unknown" event: the Fast Pair pop-up's text right away
     * and 5 and 30 seconds later, Play services' Fast Pair battery log lines from the minutes before, and the
     * earbuds' connection. Reads only; at most once a minute; the folder never grows past EVIDENCE_CAP_BYTES.
     * Runs on its own thread, so the fix itself is never delayed.
     */
    private fun recordEvidence(d: BluetoothDevice, what: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEvidenceAt < EVIDENCE_MIN_GAP_MS) return
        lastEvidenceAt = now
        val connectedNow = connected(d)
        Thread({
            try {
                val used = EVIDENCE_DIR.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (used > EVIDENCE_CAP_BYTES) { log("evidence folder is full; not recording"); return@Thread }
                val stamp = SimpleDateFormat("yyyy-MM-dd/HHmmss", Locale.US).format(Date())
                val f = File(EVIDENCE_DIR, "$stamp.txt")
                f.parentFile?.mkdirs()
                val out = StringBuilder()
                out.append("event ${now()}: $what\n")
                out.append("device: ${android.os.Build.MODEL}; buds connected to this device: $connectedNow\n")
                out.append("pop-up now: ${popupText()}\n")
                val logs = runBounded(listOf("/system/bin/logcat", "-d", "-b", "main", "-v", "threadtime", "-t", "6000"),
                    4 * 1024 * 1024, LOG_DEADLINE_MS)
                out.append("--- Play services Fast Pair battery lines (last 6000 log lines; ended by ${logs.ended}) ---\n")
                logs.text.lineSequence().filter { line ->
                    line.contains("FastPairBattery") || line.contains("DeviceInfoGroupListener") || line.contains("BatteryNotification") ||
                        line.contains("ConnectionSwitch") || line.contains("SassDevice") || line.contains("EventStream")
                }.toList().takeLast(400).forEach { out.append(it).append('\n') }
                SystemClock.sleep(5_000)
                out.append("pop-up 5 s later: ${popupText()}\n")
                SystemClock.sleep(25_000)
                out.append("pop-up 30 s later: ${popupText()}\n")
                f.writeText(out.toString())
                log("evidence saved: ${f.path}")
            } catch (e: Throwable) {
                log("evidence recording failed: $e")
            }
        }, "budsfix-evidence").apply { isDaemon = true }.start()
    }

    /** The Fast Pair battery pop-up's text from the active notifications, or a note that none is showing. */
    private fun popupText(): String {
        val r = runBounded(listOf("/system/bin/dumpsys", "notification", "--noredact"), 4 * 1024 * 1024, LOG_DEADLINE_MS)
        if (r.ended != "done") return "(could not read the notifications: ${r.ended})"
        val active = r.text.substringBefore("mArchive=")
        val ticker = active.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("tickerText=") && it.contains(" Battery: ") }
        return ticker?.removePrefix("tickerText=") ?: "(no Fast Pair battery pop-up showing)"
    }

    // ---- Wireless debugging keeper (only with KEEP_WD_FILE) -----------------------------------------------

    /**
     * Android switches Wireless debugging off whenever Wi-Fi disconnects, even for a second. This switches it
     * back on, but only when Android's own log says a Wi-Fi drop caused it ("... Disabling adbwifi." from
     * AdbDebuggingManager), never when the person switched it off, only once Wi-Fi is connected again, and at
     * most 3 times an hour.
     */
    private fun keepWirelessDebugging() {
        if (!KEEP_WD_FILE.exists()) return
        try {
            val setting = runBounded(listOf("/system/bin/settings", "get", "global", "adb_wifi_enabled"), 4096, 5_000)
            if (setting.ended != "done") return
            val now = System.currentTimeMillis()
            if (setting.text.trim() == "1") { wdLastOnAt = now; health("wireless-debugging", "ok", "Wireless debugging is on"); return }
            // Off. Was it Android, because of Wi-Fi? Look only at lines since it was last seen on (or the last
            // 10 minutes if this helper has not seen it on yet).
            val since = if (wdLastOnAt > 0) wdLastOnAt else now - 10 * 60_000
            val log = runBounded(listOf("/system/bin/logcat", "-d", "-b", "system", "-v", "epoch", "-s", "AdbDebuggingManager:I"),
                LOG_CAP_BYTES, LOG_DEADLINE_MS)
            if (log.ended != "done") return
            val cause = log.text.lines().lastOrNull { line ->
                val t = line.trim().substringBefore(' ').toDoubleOrNull()?.times(1000)?.toLong() ?: return@lastOrNull false
                t >= since && line.contains("Disabling adbwifi") &&
                    (line.contains("Network disconnected") || line.contains("Wifi disabled") || line.contains("wifi network change") ||
                        line.contains("BSSID"))
            }
            if (cause == null) {
                health("wireless-debugging", "info", "Wireless debugging is off, and not because of Wi-Fi, so it is left off")
                return
            }
            val wifi = runBounded(listOf("/system/bin/cmd", "wifi", "status"), 64 * 1024, 5_000)
            if (wifi.ended != "done" || !wifi.text.lines().any { it.startsWith("Wifi is connected") }) return // next check
            while (wdSwitchOns.isNotEmpty() && now - wdSwitchOns.first() > 60 * 60_000) wdSwitchOns.removeFirst()
            if (wdSwitchOns.size >= 3) {
                health("wireless-debugging", "attention", "Wi-Fi keeps dropping, so Wireless debugging was switched back on 3 times " +
                    "this hour; not switching it on again until the hour is over")
                return
            }
            val reason = cause.substringAfter("AdbDebuggingManager:").trim()
            runBounded(listOf("/system/bin/settings", "put", "global", "adb_wifi_enabled", "1"), 4096, 5_000)
            val after = runBounded(listOf("/system/bin/settings", "get", "global", "adb_wifi_enabled"), 4096, 5_000)
            wdSwitchOns.addLast(now)
            if (after.text.trim() == "1") {
                wdLastOnAt = now
                log("Wireless debugging: Android switched it off after a Wi-Fi drop (\"$reason\"); switched it back on")
                health("wireless-debugging", "ok", "switched Wireless debugging back on after a Wi-Fi drop at ${now()}")
            } else {
                log("Wireless debugging: tried to switch it back on after a Wi-Fi drop, but it is still off")
            }
        } catch (e: Throwable) {
            log("Wireless debugging check failed: $e")
        }
    }

    // ---- The saved reading (survives helper restarts within the same boot) ---------------------------

    private fun bootId(): String = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }

    /** Writes every watched headset's latest real reading and connection state to LAST_REAL_FILE. */
    private fun saveReadings() {
        val boot = bootId()
        if (boot.isEmpty()) return
        val body = StringBuilder()
        for ((addr, w) in watched) {
            val r = w.lastReal ?: continue
            body.append(listOf(addr, r.left, r.leftCharging, r.right, r.rightCharging, r.at, w.connectedSinceReal, w.checkedAt, boot)
                .joinToString("|")).append('\n')
        }
        try {
            val tmp = File(LAST_REAL_FILE.path + ".new")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(LAST_REAL_FILE)) LAST_REAL_FILE.writeText(body.toString())
        } catch (e: Throwable) {
            log("could not save the last real reading: $e")
        }
    }

    /**
     * Takes back the reading a previous helper saved, if it is from this boot. Its time is kept, so the
     * 10 minute rule still applies to its real age. "Connected since" is kept only if the previous helper
     * saw the earbuds connected less than RESTORE_GAP_MS ago and they are connected now.
     */
    private fun restoreReading(w: Watched) {
        try {
            val boot = bootId()
            if (boot.isEmpty() || !LAST_REAL_FILE.exists()) return
            val f = LAST_REAL_FILE.readLines().map { it.split("|") }
                .firstOrNull { it.size == 9 && it[0] == w.device.address && it[8] == boot } ?: return
            val at = f[5].toLongOrNull() ?: return
            val now = SystemClock.elapsedRealtime()
            if (at > now) return
            val checkedAt = f[7].toLongOrNull() ?: 0L
            w.lastReal = Reading(f[1], f[2], f[3], f[4], at)
            w.connectedSinceReal = f[6] == "true" && now - checkedAt <= RESTORE_GAP_MS && connected(w.device)
            if (w.connectedSinceReal) w.checkedAt = now
            log("restored the last real reading saved before this start: L=${f[1]}% R=${f[3]}% " +
                "(charging ${f[2]}/${f[4]}), reported ${(now - at) / 1000} s ago" + if (w.connectedSinceReal) ", buds connected since" else "")
            health("restore-${w.device.address}", "ok", "kept the last real reading across the restart at ${now()}")
        } catch (e: Throwable) {
            log("could not restore the last real reading: $e")
        }
    }

    // ---- LE Audio auto-connect (second job) -----------------------------------------------------------

    private class BtProcess(val pid: String, val id: String, val ageMs: Long)

    private var btPidCache: String? = null

    private fun cmdline(pid: String): String? = try {
        File("/proc/$pid/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    } catch (_: Throwable) { null }

    /**
     * The running Bluetooth process, or null if there is none or more than one (a user switch). While the
     * process found last time is still alive, only that one is checked; otherwise all processes are read.
     */
    private fun bluetoothProcess(): BtProcess? {
        btPidCache?.let { pid ->
            val name = cmdline(pid)
            if (name != null && (name in BT_NAMES || BT_NAME_PATTERN.matches(name))) return describeBt(pid)
            btPidCache = null
        }
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
        btPidCache = found[0]
        return describeBt(found[0])
    }

    private fun describeBt(pid: String): BtProcess? {
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

    /** Read-only test: what the guard sees right now (no knock). */
    private fun testAudio() {
        Looper.prepareMainLooper()
        try { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null) } catch (_: Throwable) {}
        println("adapter: way ${shellAdapter()}")
        val d = adapter.bondedDevices.firstOrNull { isPixelBuds(it) } ?: run { println("no Pixel Buds paired"); return }
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager?
        println("context: ${context != null}; audio manager: ${am != null}")
        println("isMusicActive: ${try { am?.isMusicActive } catch (e: Throwable) { "failed: $e" }}")
        val getActive = try { BluetoothAdapter::class.java.getMethod("getActiveDevices", Int::class.javaPrimitiveType) } catch (e: Throwable) { println("getActiveDevices missing: $e"); null }
        for (profile in listOf(LE_AUDIO, 2)) println("active devices for profile $profile: " +
            (try { (getActive?.invoke(adapter, profile) as List<*>?)?.map { (it as? BluetoothDevice)?.address?.takeLast(5) } } catch (e: Throwable) { "failed: ${e.cause ?: e}" }))
        println("buds connected here: ${connected(d)}; audioToBuds: ${audioToBuds(d)}")
        println("latest report in Play services' log: ${latestReportFromLog()?.let { "${(System.currentTimeMillis() - it) / 1000} s ago" } ?: "none in 3 minutes"}")
        System.out.flush()
    }

    /** Test run by hand: one knock now, and whether audio plays to the buds. Uses no saved state. */
    private fun testKnock() {
        Looper.prepareMainLooper()
        try { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null) } catch (_: Throwable) {}
        println("adapter: way ${shellAdapter()}")
        val thread = HandlerThread("budsfix").apply { start() }
        worker = Handler(thread.looper)
        val d = adapter.bondedDevices.firstOrNull { isPixelBuds(it) } ?: run { println("no Pixel Buds paired"); return }
        println("buds connected here: ${connected(d)}; audio playing to them: ${audioToBuds(d)}")
        val fromLog = latestReportFromLog()
        println("latest report in Play services' log: ${fromLog?.let { "${(System.currentTimeMillis() - it) / 1000} s ago" } ?: "none in the last 3 minutes"}")
        println(if (KNOCK_RFCOMM_FILE.exists()) "knock form: RFCOMM (knock-rfcomm file present)" else "Play services' LE stream channel: ${streamPsmFor(d) ?: "not found"}")
        knock(d, "test by hand")
        SystemClock.sleep(KNOCK_VERIFY_MS + 1_500)
        System.out.flush()
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
