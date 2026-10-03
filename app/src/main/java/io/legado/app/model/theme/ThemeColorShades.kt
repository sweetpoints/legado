package io.legado.app.model.theme

import kotlin.math.abs

/** Preserve the old color picker's twelve lighter/darker sRGB shades. */
internal fun themeColorShades(color: Int): List<Int> = listOf(.9, .7, .5, .333, .166, -.125, -.25, -.375, -.5, -.675, -.7, -.775).map { percent ->
    val target = if (percent < 0) 0 else 255
    fun channel(shift: Int): Int { val value = color ushr shift and 255; return value + Math.round((target - value) * abs(percent)).toInt() }
    (color and 0xff000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
