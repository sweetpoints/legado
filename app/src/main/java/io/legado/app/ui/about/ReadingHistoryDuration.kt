package io.legado.app.ui.about

internal fun formatDuring(mss: Long, useDays: Boolean = false, showSeconds: Boolean = true): String {
    val totalHours = mss / (1000 * 60 * 60)
    val days = if (useDays) totalHours / 24 else 0
    val hours = if (useDays) totalHours % 24 else totalHours
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val h = if (hours > 0) "${hours}小时" else ""
    val d = if (days > 0) "${days}天" else ""
    val m = if (minutes > 0) "${minutes}分钟" else ""
    val s = if (showSeconds && seconds > 0) "${seconds}秒" else ""
    return "$d$h$m$s".ifBlank { if (showSeconds) "0秒" else "0分钟" }
}
