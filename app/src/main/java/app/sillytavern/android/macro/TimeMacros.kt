package app.sillytavern.android.macro

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object TimeMacros {
    fun register() {
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        MacroRegistry.register(MacroDef(listOf("time", "isotime"), 0, 0) { it.ctx.now.format(hm) })
        MacroRegistry.register(MacroDef(listOf("date", "isodate"), 0, 0) { it.ctx.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) })
        MacroRegistry.register(MacroDef(listOf("datetimeformat"), 1, 1) { format(it.ctx.now, it.arg(0)) })
        MacroRegistry.register(MacroDef(listOf("idleDuration", "idle_duration"), 0, 0) { c ->
            val since = c.ctx.idleSince ?: return@MacroDef "just now"
            val secs = Duration.between(since, c.ctx.now.atZone(c.ctx.zone).toInstant()).seconds.coerceAtLeast(0)
            if (secs < 5) "just now" else humanize(secs)
        })
        MacroRegistry.register(MacroDef(listOf("timeDiff"), 2, 2) { c ->
            val a = parse(c.arg(0)) ?: return@MacroDef null
            val b = parse(c.arg(1)) ?: return@MacroDef null
            val secs = Duration.between(b, a).seconds
            val h = humanize(kotlin.math.abs(secs))
            if (secs < 0) "$h ago" else if (secs > 0) "in $h" else "just now"
        })
        val utc = Regex("(?i)^time_utc([+-]\\d{1,2})$")
        MacroRegistry.registerDynamic { name ->
            val m = utc.find(name) ?: return@registerDynamic null
            val off = m.groupValues[1].toInt()
            if (off !in -12..14) return@registerDynamic null
            MacroDef(listOf(name), 0, 0) { c ->
                c.ctx.now.atZone(c.ctx.zone).withZoneSameInstant(ZoneOffset.ofHours(off)).format(hm)
            }
        }
    }

    private fun parse(s: String): LocalDateTime? {
        val t = s.trim().replace(' ', 'T')
        return runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atStartOfDay() }.getOrNull()
    }

    /** Moment-style humanized duration (no suffix). */
    fun humanize(secs: Long): String {
        val min = (secs + 30) / 60; val hours = Math.round(secs / 3600.0); val days = Math.round(secs / 86400.0)
        fun n(v: Long, unit: String) = if (v == 1L) "a $unit".replace("a hour", "an hour") else "$v ${unit}s"
        return when {
            secs < 45 -> "a few seconds"
            secs < 90 -> "a minute"
            secs < 45 * 60 -> "$min minutes"
            secs < 90 * 60 -> "an hour"
            secs < 22 * 3600 -> n(hours, "hour")
            secs < 36 * 3600 -> "a day"
            secs < 26 * 86400 -> n(days, "day")
            secs < 45 * 86400 -> "a month"
            secs < 320 * 86400 -> n(Math.round(secs / 2629800.0), "month")
            secs < 548 * 86400 -> "a year"
            else -> n(Math.round(secs / 31557600.0), "year")
        }
    }

    private val tokens = listOf("YYYY", "MMMM", "dddd", "MMM", "ddd", "YY", "MM", "DD", "HH", "hh", "mm", "ss", "M", "D", "H", "h", "m", "s", "A", "a")

    /** Subset of moment.js format tokens. `[text]` is literal. */
    fun format(t: LocalDateTime, fmt: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < fmt.length) {
            if (fmt[i] == '[') { val e = fmt.indexOf(']', i); if (e > 0) { sb.append(fmt, i + 1, e); i = e + 1; continue } }
            val tok = tokens.firstOrNull { fmt.startsWith(it, i) }
            if (tok == null) { sb.append(fmt[i]); i++; continue }
            sb.append(when (tok) {
                "YYYY" -> "%04d".format(t.year); "YY" -> "%02d".format(t.year % 100)
                "MMMM" -> t.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH); "MMM" -> t.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                "MM" -> "%02d".format(t.monthValue); "M" -> t.monthValue.toString()
                "dddd" -> t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH); "ddd" -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                "DD" -> "%02d".format(t.dayOfMonth); "D" -> t.dayOfMonth.toString()
                "HH" -> "%02d".format(t.hour); "H" -> t.hour.toString()
                "hh" -> "%02d".format((t.hour + 11) % 12 + 1); "h" -> ((t.hour + 11) % 12 + 1).toString()
                "mm" -> "%02d".format(t.minute); "m" -> t.minute.toString()
                "ss" -> "%02d".format(t.second); "s" -> t.second.toString()
                "A" -> if (t.hour < 12) "AM" else "PM"; else -> if (t.hour < 12) "am" else "pm"
            })
            i += tok.length
        }
        return sb.toString()
    }
}
