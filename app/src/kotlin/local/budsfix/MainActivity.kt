package local.budsfix

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * A normal app window (closing it never affects the fix). It shows whether the fix is running and
 * what it has done, and lets you check it now.
 */
class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var details: TextView
    private lateinit var result: TextView
    private lateinit var checkButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 2, 0, pad / 2)
            setTextIsSelectable(true)
        }
        state = text(22f, true)
        details = text(15f)
        result = text(14f)
        checkButton = Button(this).apply { text = "Check now"; setOnClickListener { runCheck() } }
        val about = text(14f).apply {
            text = "Google's Fast Pair sometimes stores your earbuds' left and right battery as \"unknown\" " +
                "while the case has a real level, so the Batteries widget and Settings show only the case. " +
                "Buds Battery Fix watches for exactly that moment and puts back the buds' latest real levels " +
                "within milliseconds. It only uses a reading the buds reported while it was watching, within " +
                "the last 10 minutes or with the buds connected ever since. Every real report from the buds " +
                "passes through untouched, so new levels always show at once. It never makes up a number.\n\n" +
                "It also keeps your Pixel Buds on LE Audio's auto-connect list. When Bluetooth starts at a " +
                "restart, it can take the buds off that list by mistake, so they no longer connect by " +
                "themselves. Once after every Bluetooth start, Buds Battery Fix looks in Bluetooth's own log. " +
                "If the buds are on the list or connected, it changes nothing. If not, and LE Audio is allowed " +
                "for them, it re-applies their LE Audio setting, which puts them back on the list, and then " +
                "confirms it. Classic Bluetooth audio is never touched.\n\n" +
                "After every start, and after every system or app update, it checks that everything it relies " +
                "on still works. If something stopped working, this window says \"Needs attention\" with the " +
                "reason, and you get a notification.\n\n" +
                "It runs on this device itself, without the Linux Terminal. This app starts it again after " +
                "every restart and checks it every 15 minutes. Its log is in ${Fixer.DIR} on this device. " +
                "Nothing is ever deleted."
        }
        column.addView(state)
        column.addView(details)
        column.addView(checkButton)
        column.addView(result)
        column.addView(about)
        setContentView(ScrollView(this).apply { addView(column) })
        show()
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        val p = Prefs.get(this)
        val lastCheck = p.getLong(Status.LAST_CHECK, 0)
        val text = p.getString(Status.LAST_TEXT, "") ?: ""
        val lines = text.lines()
        val running = lines.any { it == "running" }
        val attention = Fixer.attentionIn(text)
        when {
            lastCheck == 0L -> {
                state.text = "Not checked yet"
                state.setTextColor(Color.LTGRAY)
            }
            running && attention.isNotEmpty() -> {
                state.text = "Needs attention"
                state.setTextColor(Color.rgb(0xf2, 0xc0, 0x5c))
            }
            running -> {
                state.text = "Fixing"
                state.setTextColor(Color.rgb(0x6d, 0xd5, 0x8c))
            }
            else -> {
                state.text = "Not running"
                state.setTextColor(Color.rgb(0xf2, 0x8b, 0x82))
            }
        }
        val out = ArrayList<String>()
        lines.firstOrNull { it.startsWith("fixes this month:") }?.let { out.add(it.replaceFirstChar { c -> c.uppercase() }) }
        lines.firstOrNull { it.startsWith("last fix:") }?.substringAfter("last fix:")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { out.add("Last fix: $it") }
        if (lastCheck > 0) out.add("Last check by this app: ${time(lastCheck)}")
        if (!running && text.isNotEmpty()) out.add(lines.first())
        for (a in attention) out.add("Needs attention: $a")
        lines.firstOrNull { it.startsWith("self-check:") }?.let { out.add(it.replaceFirstChar { c -> c.uppercase() }) }
        details.text = out.joinToString("\n")
    }

    private fun runCheck() {
        checkButton.isEnabled = false
        result.text = "Checking..."
        Thread {
            val msg = try {
                val r = Fixer.check(applicationContext)
                Status.record(applicationContext, r)
                if (r.running) "The fix is running." else r.text.lineSequence().first()
            } catch (e: Exception) {
                Status.recordError(applicationContext, e.toString())
                "The check failed: $e"
            }
            runOnUiThread {
                result.text = msg
                checkButton.isEnabled = true
                show()
            }
        }.start()
    }

    private fun time(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms))
}
