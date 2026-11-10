package local.budsfix

import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal adb client for Wireless debugging (adb over TLS), enough to run shell commands.
 * Protocol: CNXN, server STLS, client STLS, TLS 1.3 with our client certificate (whose key was
 * paired with this Googlebook), server CNXN, then OPEN "exec:cmd", WRTE..., CLSE for each command.
 */
class AdbClient private constructor(private val raw: Socket) : Closeable {
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var nextLocalId = 1

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Runs a command as the shell user and returns its output (bounded in time and size). */
    fun exec(command: String): String {
        val local = nextLocalId++ // a fresh stream id per command, so late packets cannot mix streams
        write(A_OPEN, local, 0, "exec:$command\u0000".toByteArray(Charsets.UTF_8))
        val result = ByteArrayOutputStream()
        var remote = 0
        val deadline = SystemClock.uptimeMillis() + EXEC_DEADLINE_MS
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) {
                close() // the stream is in an unknown state: hang up
                throw IOException("the command did not finish within ${EXEC_DEADLINE_MS / 1000} s")
            }
            val m = read()
            if (m.arg1 != local) continue // belongs to an earlier stream
            when (m.cmd) {
                A_OKAY -> remote = m.arg0
                A_WRTE -> {
                    remote = m.arg0
                    if (result.size() + m.data.size > EXEC_MAX_OUTPUT) {
                        close()
                        throw IOException("the command produced more than ${EXEC_MAX_OUTPUT / 1024} KB of output")
                    }
                    result.write(m.data)
                    write(A_OKAY, local, remote, ByteArray(0))
                }
                A_CLSE -> {
                    write(A_CLSE, local, if (m.arg0 != 0) m.arg0 else remote, ByteArray(0))
                    return result.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun write(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (x in data) sum += x.toInt() and 0xff
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd.inv()).put(data)
        output.write(b.array())
        output.flush()
    }

    private fun read(): Msg {
        val h = ByteArray(24)
        input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad payload length $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    override fun close() {
        try {
            raw.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_STLS = 0x534c5453
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_WRTE = 0x45545257
        private const val A_CLSE = 0x45534c43
        private const val A_AUTH = 0x48545541
        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024

        /** A whole command, including a supervisor start or stop, must finish within this. */
        const val EXEC_DEADLINE_MS = 120_000L
        const val EXEC_MAX_OUTPUT = 1024 * 1024

        /** Connects and completes the TLS handshake with a PKCS#12 key store holding the paired key. */
        fun connect(host: String, port: Int, pkcs12: ByteArray, password: CharArray, timeoutMs: Int): AdbClient {
            val s = Socket()
            val c = AdbClient(s)
            try {
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.tcpNoDelay = true
                c.input = DataInputStream(s.getInputStream())
                c.output = s.getOutputStream()
                c.write(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
                var m = c.read()
                if (m.cmd == A_AUTH) throw IOException("the device wants legacy RSA authentication, not Wireless debugging TLS")
                if (m.cmd != A_STLS) throw IOException("unexpected reply " + Integer.toHexString(m.cmd))
                c.write(A_STLS, A_STLS_VERSION, 0, ByteArray(0))

                val ks = KeyStore.getInstance("PKCS12")
                ks.load(ByteArrayInputStream(pkcs12), password)
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, password)
                // adbd lists hashes of paired keys as acceptable issuers; the stock key manager would
                // then offer no certificate, so always present ours.
                val base = kmf.keyManagers[0] as X509KeyManager
                val alias = ks.aliases().nextElement()
                val keyManager = object : X509KeyManager {
                    override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s2: Socket?) = alias
                    override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s2: Socket?): String? = null
                    override fun getCertificateChain(a: String?): Array<X509Certificate> = base.getCertificateChain(a)
                    override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(alias)
                    override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = null
                    override fun getPrivateKey(a: String?): PrivateKey = base.getPrivateKey(a)
                }
                // adbd's certificate is self-signed and not pinned by adb itself either. Before any
                // command is sent, the caller proves it reached Android's adb service (see Adb.prove).
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val ctx = SSLContext.getInstance("TLSv1.3")
                ctx.init(arrayOf(keyManager), arrayOf(trustAll), null)
                val tls = ctx.socketFactory.createSocket(s, host, port, true) as SSLSocket
                tls.useClientMode = true
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.startHandshake()
                c.input = DataInputStream(tls.inputStream)
                c.output = tls.outputStream
                m = c.read()
                if (m.cmd != A_CNXN) throw IOException("no CNXN after TLS (key not paired?)")
                // Connecting is quick; a command may run quietly for a while (a restart waits for the
                // old supervisor to exit), so reads get a generous limit from here on, still bounded.
                s.soTimeout = 75_000
                return c
            } catch (e: Exception) {
                c.close()
                throw e
            }
        }
    }
}
