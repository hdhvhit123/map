package app.terratrace.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val vi = Locale("vi", "VN")

    fun distance(m: Double): String = when {
        m < 1000 -> "${m.toInt()} m"
        m < 100_000 -> String.format(vi, "%.2f km", m / 1000)
        else -> String.format(vi, "%.0f km", m / 1000)
    }

    fun distanceValue(m: Double): Pair<String, String> = when {
        m < 1000 -> m.toInt().toString() to "m"
        m < 100_000 -> String.format(vi, "%.2f", m / 1000) to "km"
        else -> String.format(vi, "%.0f", m / 1000) to "km"
    }

    fun meters(m: Double?): String = if (m == null) "–" else String.format(vi, "%,d m", m.toInt())

    fun speedKmh(mps: Double?): String = if (mps == null) "–" else String.format(vi, "%.1f", mps * 3.6)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(vi, "%d:%02d:%02d", h, m, sec) else String.format(vi, "%02d:%02d", m, sec)
    }

    fun durationWords(ms: Long): String {
        val m = ms / 60_000
        val h = m / 60
        return if (h > 0) "${h} giờ ${m % 60} phút" else "${m} phút"
    }

    fun date(t: Long?): String = if (t == null) "" else SimpleDateFormat("dd/MM/yyyy", vi).format(Date(t))
    fun dateTime(t: Long?): String = if (t == null) "" else SimpleDateFormat("HH:mm · dd/MM/yyyy", vi).format(Date(t))
    fun time(t: Long?): String = if (t == null) "" else SimpleDateFormat("HH:mm", vi).format(Date(t))

    fun dateRange(a: Long?, b: Long?): String {
        if (a == null) return ""
        val da = date(a); val db = date(b ?: a)
        return if (da == db) da else "${SimpleDateFormat("dd/MM", vi).format(Date(a))} – $db"
    }

    fun bytes(b: Long): String = when {
        b < 1024 * 1024 -> String.format(vi, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(vi, "%.1f MB", b / 1024.0 / 1024)
        else -> String.format(vi, "%.2f GB", b / 1024.0 / 1024 / 1024)
    }
}
