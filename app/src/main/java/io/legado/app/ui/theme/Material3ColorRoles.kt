package io.legado.app.ui.theme

import io.legado.app.ui.theme.mcu.hct.Hct
import io.legado.app.ui.theme.mcu.palettes.TonalPalette
import kotlin.math.pow

/** Opaque ARGB roles; no Android Context or Compose state is needed to validate a palette. */
internal data class Material3ColorRoles(val values: Map<String, Int>) {
    operator fun get(role: String): Int = values.getValue(role)
}

internal fun contrastRatio(foreground: Int, background: Int): Double {
    val visible = compositeColor(foreground, background)
    val a = relativeLuminance(visible)
    val b = relativeLuminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

private fun relativeLuminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val value = ((color ushr shift) and 255) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun compositeColor(color: Int, background: Int): Int {
    val alpha = (color ushr 24) / 255.0
    fun channel(shift: Int): Int =
        ((((color ushr shift) and 255) * alpha + ((background ushr shift) and 255) * (1 - alpha)) +
                0.5)
            .toInt()
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Select by actual contrast, rather than the incorrect luminance > 0.5 heuristic. */
internal fun contrastingForeground(background: Int): Int =
    if (
        contrastRatio(0xFF000000.toInt(), background) >=
            contrastRatio(0xFFFFFFFF.toInt(), background)
    )
        0xFF000000.toInt()
    else 0xFFFFFFFF.toInt()

internal fun material3ColorRoles(
    primary: Int,
    primaryContainerSeed: Int,
    accent: Int,
    background: Int,
    bottomBackground: Int,
    preferredText: Int,
    preferredSecondaryText: Int,
): Material3ColorRoles {
    val bg = compositeColor(background, 0xFFFFFFFF.toInt())
    val primaryColor = compositeColor(primary, bg)
    val secondaryColor = compositeColor(accent, bg)
    val foreground = contrastingForeground(bg)
    val light = foreground == 0xFF000000.toInt()
    val p = Hct.fromInt(primaryColor)
    val n = TonalPalette.fromHueAndChroma(p.hue, 4.0)
    val variant = Hct.fromInt(bottomBackground)
    val nv = TonalPalette.fromHueAndChroma(variant.hue, variant.chroma.coerceIn(8.0, 16.0))
    val pt = TonalPalette.fromInt(primaryContainerSeed)
    val st = TonalPalette.fromInt(secondaryColor)
    val tt = TonalPalette.fromHueAndChroma((p.hue + 60.0) % 360.0, 24.0)
    val et = TonalPalette.fromHueAndChroma(25.0, 84.0)
    val tone = Hct.fromInt(bg).tone
    fun surface(palette: TonalPalette, offset: Double): Int {
        var target = (tone + offset).coerceIn(0.0, 100.0)
        var color = palette.tone(target.toInt())
        while (contrastRatio(foreground, color) < 4.5) {
            target = (target + if (light) 1.0 else -1.0).coerceIn(0.0, 100.0)
            color = palette.tone(target.toInt())
        }
        return color
    }
    val roles = linkedMapOf<String, Int>("background" to bg, "surface" to bg)
    val offsets =
        if (light) listOf(4.0, -2.0, -4.0, -6.0, -8.0) else listOf(-4.0, 2.0, 4.0, 6.0, 8.0)
    listOf(
            "surfaceContainerLowest",
            "surfaceContainerLow",
            "surfaceContainer",
            "surfaceContainerHigh",
            "surfaceContainerHighest",
        )
        .zip(offsets)
        .forEach { (role, offset) -> roles[role] = surface(n, offset) }
    roles["surfaceBright"] = surface(n, if (light) 4.0 else 12.0)
    roles["surfaceDim"] = surface(n, if (light) -10.0 else 0.0)
    roles["surfaceVariant"] = surface(nv, if (light) -6.0 else 8.0)
    val surfaces = roles.values.toList()
    fun readable(preferred: Int, backgrounds: List<Int>): Int {
        val opaque = compositeColor(preferred, bg)
        return if (backgrounds.all { contrastRatio(opaque, it) >= 4.5 }) opaque else foreground
    }
    roles["onBackground"] = readable(preferredText, listOf(bg))
    roles["onSurface"] = readable(preferredText, surfaces)
    roles["onSurfaceVariant"] = readable(preferredSecondaryText, surfaces)
    fun semanticForeground(
        seed: Int,
        backgrounds: List<Int> = surfaces,
        minimum: Double = 4.5,
    ): Int {
        if (backgrounds.all { contrastRatio(seed, it) >= minimum }) return seed
        val palette = TonalPalette.fromInt(seed)
        val seedTone = Hct.fromInt(seed).tone
        return (0..100)
            .filter { candidate ->
                backgrounds.all { contrastRatio(palette.tone(candidate), it) >= minimum }
            }
            .minBy { kotlin.math.abs(it - seedTone) }
            .let(palette::tone)
    }
    roles["accentForeground"] = semanticForeground(secondaryColor)
    fun pair(role: String, color: Int) {
        roles[role] = color
        roles["on" + role.replaceFirstChar { it.uppercase() }] = contrastingForeground(color)
    }
    pair("primary", semanticForeground(primaryColor))
    pair("secondary", semanticForeground(secondaryColor))
    pair("tertiary", semanticForeground(tt.tone(if (light) 40 else 80)))
    pair("primaryContainer", pt.tone(if (light) 90 else 30))
    pair("secondaryContainer", st.tone(if (light) 90 else 30))
    pair("tertiaryContainer", tt.tone(if (light) 90 else 30))
    pair("error", semanticForeground(et.tone(if (light) 40 else 80)))
    pair("errorContainer", et.tone(if (light) 90 else 30))
    roles["inverseSurface"] = n.tone(if (light) 20 else 90)
    roles["inverseOnSurface"] = contrastingForeground(roles.getValue("inverseSurface"))
    roles["inversePrimary"] =
        semanticForeground(
            TonalPalette.fromInt(primaryColor).tone(if (light) 80 else 40),
            listOf(roles.getValue("inverseSurface")),
        )
    roles["surfaceTint"] = roles.getValue("primary")
    roles["outline"] = semanticForeground(n.tone(if (light) 50 else 60), minimum = 3.0)
    roles["outlineVariant"] = nv.tone(if (light) 80 else 30)
    roles["scrim"] = 0xFF000000.toInt()
    listOf("primary" to TonalPalette.fromInt(primaryColor), "secondary" to st, "tertiary" to tt)
        .forEach { (role, palette) ->
            roles[role + "Fixed"] = palette.tone(90)
            roles[role + "FixedDim"] = palette.tone(80)
            val fixedForeground = contrastingForeground(palette.tone(80))
            roles["on" + role.replaceFirstChar { it.uppercase() } + "Fixed"] = fixedForeground
            roles["on" + role.replaceFirstChar { it.uppercase() } + "FixedVariant"] =
                palette.tone(30).let {
                    if (listOf(80, 90).all { tone -> contrastRatio(it, palette.tone(tone)) >= 4.5 })
                        it
                    else fixedForeground
                }
        }
    return Material3ColorRoles(roles)
}
