package local.budsfix

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Buds Battery Fix's own connection to Wireless debugging on this device (127.0.0.1), with its
 * OWN paired key ("BudsBatteryFix@<device>"), separate from every other helper's key.
 */
object Adb {
    const val TAG = "BudsFix"
    private const val P12_PASS = "p12pass"
    private const val PORT = "adbPort"

    /** Nonces answered by ControlReceiver; only the adb shell or the system can send to it. */
    val proven: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun keyFile(c: Context) = File(c.filesDir, "adb.p12")

    fun provisioned(c: Context) = keyFile(c).exists() && Prefs.get(c).contains(P12_PASS)

    fun storeKey(c: Context, p12: ByteArray, pass: String) {
        val tmp = File(c.filesDir, "adb.p12.new")
        tmp.outputStream().use { out ->
            out.write(p12)
            out.fd.sync()
        }
        if (!tmp.renameTo(keyFile(c))) throw java.io.IOException("could not store the key")
        Prefs.get(c).edit().putString(P12_PASS, pass).remove(PORT).commit()
    }

    /** A proven connection to this Googlebook's adb service, or null if it is not reachable now. */
    fun connect(ctx: Context): AdbClient? {
        if (!provisioned(ctx)) return null
        val p12 = keyFile(ctx).readBytes()
        val pass = (Prefs.get(ctx).getString(P12_PASS, "") ?: "").toCharArray()
        val saved = Prefs.get(ctx).getInt(PORT, -1)
        if (saved > 0) proven(ctx, saved, p12, pass)?.let { return it }
        for (port in discoverOwnPorts(ctx, 15_000)) {
            val c = proven(ctx, port, p12, pass)
            if (c != null) {
                Prefs.get(ctx).edit().putInt(PORT, port).apply()
                return c
            }
        }
        return null
    }

    /** Connects and proves it is Android's adb service before anything else is sent. */
    private fun proven(ctx: Context, port: Int, p12: ByteArray, pass: CharArray): AdbClient? {
        val c = try {
            AdbClient.connect("127.0.0.1", port, p12, pass, 5000)
        } catch (e: Exception) {
            Log.i(TAG, "port $port: $e")
            return null
        }
        val nonce = hex(16)
        try {
            val user = Process.myUid() / 100000
            c.exec("am broadcast --user $user -n ${ctx.packageName}/.ControlReceiver -a local.budsfix.PROVE --es nonce $nonce")
        } catch (e: Exception) {
            c.close()
            return null
        }
        val until = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < until) {
            if (proven.remove(nonce)) return c
            SystemClock.sleep(50)
        }
        Log.w(TAG, "port $port did not prove to be Android's adb service; ignored")
        c.close()
        return null
    }

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Wireless debugging ports advertised by this device itself (not by phones on the Wi-Fi). */
    private fun discoverOwnPorts(ctx: Context, timeoutMs: Long): List<Int> {
        val own = HashSet<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (a in Collections.list(ni.inetAddresses)) a.hostAddress?.let { own.add(it) }
        } catch (_: Exception) {
        }
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val found = Collections.synchronizedList(ArrayList<Int>())
        val gotOne = CountDownLatch(1)
        val direct = Executor { it.run() }
        val callbacks = ArrayList<NsdManager.ServiceInfoCallback>()
        var closed = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) = gotOne.countDown()
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(i: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                    override fun onServiceUpdated(s: NsdServiceInfo) {
                        val mine = s.hostAddresses.any { a -> a.hostAddress?.let { own.contains(it) } == true }
                        if (mine && s.port > 0) {
                            found.add(s.port)
                            gotOne.countDown()
                        }
                    }
                }
                synchronized(callbacks) {
                    if (closed) return
                    try {
                        nsd.registerServiceInfoCallback(info, direct, cb)
                        callbacks.add(cb)
                    } catch (_: RuntimeException) {
                    }
                }
            }
        }
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            gotOne.await(timeoutMs, TimeUnit.MILLISECONDS)
            Thread.sleep(500)
        } finally { // never leave a discovery session open
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: RuntimeException) {
            }
            synchronized(callbacks) {
                closed = true
                for (cb in callbacks) {
                    try {
                        nsd.unregisterServiceInfoCallback(cb)
                    } catch (_: RuntimeException) {
                    }
                }
            }
        }
        synchronized(found) { return LinkedHashSet(found).toList() }
    }
}
