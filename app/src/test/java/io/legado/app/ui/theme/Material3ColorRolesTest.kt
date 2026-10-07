package io.legado.app.ui.theme

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class Material3ColorRolesTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val surfaceNames =
        listOf(
            "background",
            "surface",
            "surfaceVariant",
            "surfaceBright",
            "surfaceDim",
            "surfaceContainerLowest",
            "surfaceContainerLow",
            "surfaceContainer",
            "surfaceContainerHigh",
            "surfaceContainerHighest",
        )

    private fun scheme(
        primary: Int,
        accent: Int,
        background: Int,
        text: Int = black,
        secondaryText: Int = 0x99000000.toInt(),
    ) =
        material3ColorRoles(
            primary,
            0xFF01579B.toInt(),
            accent,
            background,
            0xFFE0E0E0.toInt(),
            text,
            secondaryText,
        )

    @Test
    fun defaultBlueChoosesBlackToolbarTextAndReadableSemanticPrimary() {
        val blue = 0xFF039BE5.toInt()
        assertEquals(black, contrastingForeground(blue))
        assertTrue(contrastRatio(white, blue) < 4.5)
        val roles = scheme(blue, 0xFFFFEB3B.toInt(), 0xFFFAFAFA.toInt())
        assertNotEquals(blue, roles["primary"])
        assertEquals(contrastingForeground(roles["secondary"]), roles["onSecondary"])
        assertReadable(roles)
    }

    @Test
    fun darkPrimaryAndBrightYellowUseIndependentForegrounds() {
        val roles = scheme(0xFF121212.toInt(), 0xFFFFEB3B.toInt(), 0xFFFAFAFA.toInt())
        assertTrue(contrastRatio(roles["onSecondary"], roles["secondary"]) >= 4.5)
        assertTrue(contrastRatio(roles["onPrimaryContainer"], roles["primaryContainer"]) >= 4.5)
        assertReadable(roles)
    }

    @Test
    fun backgroundPreferencesOverrideMismatchedDayAndNightResourceText() {
        for ((background, preferred) in
            listOf(
                black to black,
                white to white,
                0xFF808080.toInt() to white,
                0xFF777777.toInt() to black,
            )) {
            val roles =
                scheme(0xFF039BE5.toInt(), 0xFFFFEB3B.toInt(), background, preferred, preferred)
            assertEquals(background, roles["background"])
            assertEquals(background, roles["surface"])
            assertReadable(roles)
        }
    }

    @Test
    fun customHueAndGrayBackgroundsKeepEveryTextAndContainerPairReadable() {
        val primaries =
            listOf(0xFF039BE5.toInt(), 0xFFFFEB3B.toInt(), 0xFF9C27B0.toInt(), 0xFF00C853.toInt())
        val golden = StringBuilder()
        fun appendGolden(primary: Int, background: Int, roles: Material3ColorRoles) {
            golden
                .append(primary.toUInt().toString(16))
                .append(':')
                .append(background.toUInt().toString(16))
                .append('\n')
            roles.values.toSortedMap().forEach { (role, color) ->
                golden.append(role).append('=').append(color.toUInt().toString(16)).append('\n')
            }
        }
        for (gray in 0..255 step 17) {
            val background = black or (gray shl 16) or (gray shl 8) or gray
            primaries.forEach { primary ->
                val roles = scheme(primary, 0xFFFFC107.toInt(), background)
                assertReadable(roles)
                appendGolden(primary, background, roles)
            }
        }
        appendGolden(
            0xFF039BE5.toInt(),
            0xFFFAFAFA.toInt(),
            scheme(0xFF039BE5.toInt(), 0xFFFFC107.toInt(), 0xFFFAFAFA.toInt()),
        )
        // Captured by executing these 65 palettes against MDC-Android 1.14.0 before
        // relocating the official Java subset: all 49 ARGB roles must stay identical.
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(golden.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertEquals("0aa4c94cd7b06863e29a6f257b9d644cd898f5fb7197a5919487b2a5e68ac763", digest)
    }

    @Test
    fun fixedRolesKeepTheirTonesAcrossLightAndDarkAndAllRolesAreExplicit() {
        val light = scheme(0xFF039BE5.toInt(), 0xFFFFEB3B.toInt(), white)
        val dark = scheme(0xFF039BE5.toInt(), 0xFFFFEB3B.toInt(), black)
        for (role in listOf("primary", "secondary", "tertiary")) {
            for (suffix in listOf("Fixed", "FixedDim", "FixedVariant")) {
                val name =
                    if (suffix == "FixedVariant")
                        "on" + role.replaceFirstChar { it.uppercase() } + suffix
                    else role + suffix
                assertEquals(name, light[name], dark[name])
            }
        }
        assertEquals(49, light.values.size)
        assertTrue(light.values.values.all { it ushr 24 == 255 })
    }

    private fun assertReadable(roles: Material3ColorRoles) {
        for (surface in surfaceNames) {
            assertTrue(
                "outline on $surface",
                contrastRatio(roles["outline"], roles[surface]) >= 3.0,
            )
            for (foreground in
                listOf(
                    "onSurface",
                    "onSurfaceVariant",
                    "primary",
                    "secondary",
                    "tertiary",
                    "error",
                    "accentForeground",
                )) {
                assertTrue(
                    "$foreground on $surface: ${contrastRatio(roles[foreground], roles[surface])}",
                    contrastRatio(roles[foreground], roles[surface]) >= 4.5,
                )
            }
        }
        assertTrue(contrastRatio(roles["onBackground"], roles["background"]) >= 4.5)
        for (role in
            listOf(
                "primary",
                "secondary",
                "tertiary",
                "error",
                "primaryContainer",
                "secondaryContainer",
                "tertiaryContainer",
                "errorContainer",
            )) {
            val foreground = "on" + role.replaceFirstChar { it.uppercase() }
            assertTrue("$foreground on $role", contrastRatio(roles[foreground], roles[role]) >= 4.5)
        }
        assertTrue(contrastRatio(roles["inverseOnSurface"], roles["inverseSurface"]) >= 4.5)
        assertTrue(contrastRatio(roles["inversePrimary"], roles["inverseSurface"]) >= 4.5)
        for (role in listOf("primary", "secondary", "tertiary")) {
            for (fixed in listOf("Fixed", "FixedDim")) {
                for (on in listOf("Fixed", "FixedVariant")) {
                    assertTrue(
                        contrastRatio(
                            roles["on" + role.replaceFirstChar { it.uppercase() } + on],
                            roles[role + fixed],
                        ) >= 4.5
                    )
                }
            }
        }
    }
}
