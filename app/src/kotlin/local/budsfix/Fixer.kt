package local.budsfix

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Installs the supervisor script (bundled in this app) into /data/local/tmp/budsfix on this device
 * and keeps the fix running, through the app's own adb connection. Every check is one short adb
 * session. The helper's code itself is taken by the script from this installed app.
 */
object Fixer {
    const val DIR = "/data/local/tmp/budsfix"
    private const val SCRIPT = "budsfix.sh"
    private val lock = Any()

    class Result(val reachable: Boolean, val running: Boolean, val stoppedByUser: Boolean, val text: String) {
        /** The helper's self-check findings that need a look (lines "attention: ..." from status). */
        val attention: List<String> get() = attentionIn(text)
    }

    fun attentionIn(text: String): List<String> =
        text.lines().filter { it.startsWith("attention: ") }.map { it.removePrefix("attention: ").trim() }

    /** Installs a changed script, restarts after an app update, and makes sure the fix is running. */
    fun check(ctx: Context, appUpdated: Boolean = false): Result = synchronized(lock) {
        val adb = Adb.connect(ctx)
            ?: return Result(false, false, false, if (Adb.provisioned(ctx))
                "Wireless debugging on this device is not reachable right now."
            else "The app has no adb key yet.")
        adb.use {
            val changed = install(ctx, it)
            val action = if (changed || appUpdated) "restart" else "ensure"
            val said = it.exec("sh $DIR/$SCRIPT $action 2>&1").trim()
            val status = it.exec("sh $DIR/$SCRIPT status 2>&1").trim()
            val first = status.lineSequence().firstOrNull() ?: ""
            Result(true, first == "running", status.contains("stopped by you"), "Supervisor said: $said\n$status")
        }
    }

    /** Copies the bundled script if its fingerprint differs from the device's copy. True if it changed. */
    private fun install(ctx: Context, adb: AdbClient): Boolean {
        val bytes = ctx.assets.open(SCRIPT).use { it.readBytes() }
        val want = sha256(bytes)
        adb.exec("mkdir -p $DIR && chmod 755 $DIR")
        val have = adb.exec("sha256sum $DIR/$SCRIPT 2>/dev/null").trim().substringBefore(' ')
        if (have == want) return false
        // Write a new copy beside the old one, check it, then put it in place in one step (a running
        // supervisor keeps reading the old copy until it is restarted).
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        adb.exec(": > $DIR/.$SCRIPT.b64")
        for (chunk in b64.chunked(3000)) adb.exec("echo -n '$chunk' >> $DIR/.$SCRIPT.b64")
        val got = adb.exec("base64 -d $DIR/.$SCRIPT.b64 > $DIR/.$SCRIPT.new && chmod 644 $DIR/.$SCRIPT.new && sha256sum $DIR/.$SCRIPT.new")
            .trim().substringBefore(' ')
        if (got != want) throw java.io.IOException("the copy of $SCRIPT did not arrive intact")
        adb.exec("mv -f $DIR/.$SCRIPT.new $DIR/$SCRIPT")
        return true
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
