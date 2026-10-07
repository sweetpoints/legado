package io.legado.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses real Material3 defaults under the production theme, without changing user preferences. */
class Material3ThemeContrastTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scheme: ColorScheme
    private val foregrounds = mutableMapOf<String, Color>()

    @Test
    fun explicitLightThemeKeepsMaterialComponentsReadable() {
        checkScene("light", colors(0xFFFAFAFA.toInt(), 0xFF039BE5.toInt(), true), true)
    }

    @Test
    fun explicitDarkThemeKeepsMaterialComponentsReadable() {
        checkScene("dark", colors(0xFF121212.toInt(), 0xFF80CBC4.toInt(), false), true)
    }

    @Test
    fun userMiddleTonePaletteKeepsMaterialComponentsReadable() {
        checkScene("user-middle-tone", colors(0xFF777777.toInt(), 0xFF999999.toInt(), true), true)
    }

    @Test
    fun whiteBackgroundDoesNotInheritUnreadableNightResourceText() {
        // Reproduce dark resource text/preferences with a light user background.
        checkScene(
            "night-resource-white-background",
            colors(0xFFFFFFFF.toInt(), 0xFF888888.toInt(), false),
            false,
        )
    }

    private fun colors(background: Int, primary: Int, light: Boolean) =
        LegadoColors(
            primary = Color(primary),
            primaryDark = Color(0xFF777777),
            onPrimary = Color.White,
            accent = Color(0xFF888800),
            background = Color(background),
            bottomBackground = Color(0xFF808080),
            textPrimary = if (light) Color.Black else Color.White,
            textSecondary = if (light) Color(0xFF777777) else Color(0xFFCCCCCC),
            textPrimaryDisabled = Color.Gray,
            textSecondaryDisabled = Color.Gray,
            isLight = light,
        )

    @Composable
    private fun ObservedText(key: String, text: String) {
        val inherited = LocalContentColor.current
        val styled = LocalTextStyle.current.color
        val actual = if (styled == Color.Unspecified) inherited else styled
        SideEffect { foregrounds[key] = actual }
        Text(text)
    }

    private fun checkScene(name: String, colors: LegadoColors, saveImage: Boolean) {
        compose.setContent {
            LegadoComposeTheme(colors = colors) {
                val actualScheme = MaterialTheme.colorScheme
                SideEffect { scheme = actualScheme }
                var dialog by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize().testTag("theme-showcase")) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Material3 theme: $name", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { dialog = true },
                            modifier = Modifier.testTag("theme-button"),
                        ) {
                            ObservedText("button", "Open dialog")
                        }
                        TextField(
                            value = "Readable field text",
                            onValueChange = {},
                            label = { ObservedText("field-label", "Field label") },
                            modifier = Modifier.fillMaxWidth().testTag("theme-field"),
                        )
                        Card(Modifier.fillMaxWidth().testTag("theme-card")) {
                            Column(Modifier.padding(16.dp)) {
                                ObservedText("card", "Card text follows the actual theme")
                            }
                        }
                    }
                }
                if (dialog) {
                    AlertDialog(
                        onDismissRequest = { dialog = false },
                        title = { ObservedText("dialog-title", "Material3 dialog") },
                        text = {
                            ObservedText(
                                "dialog-body",
                                "The dialog uses its theme surface and text roles.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { dialog = false }) { Text("Close") }
                        },
                        modifier = Modifier.testTag("theme-dialog"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-button").assertIsDisplayed()
        compose.onNodeWithTag("theme-field").assertTextContains("Readable field text")
        compose.onNodeWithTag("theme-card").assertIsDisplayed()
        compose.runOnIdle {
            assertRole("button", scheme.onPrimary, scheme.primary)
            assertRole("field-label", scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
            assertRole("card", scheme.onSurface, scheme.surfaceContainerHighest)
            assertReadable("field text", scheme.onSurface, scheme.surfaceContainerHighest)
            assertReadable("background text", scheme.onBackground, scheme.background)
        }
        if (saveImage) {
            val bitmap = compose.onNodeWithTag("theme-showcase").captureToImage().asAndroidBitmap()
            try {
                val directory =
                    checkNotNull(
                        InstrumentationRegistry.getInstrumentation()
                            .targetContext
                            .getExternalFilesDir("theme-contrast")
                    )
                assertTrue(directory.isDirectory || directory.mkdirs())
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
        compose.onNodeWithTag("theme-button").performClick()
        compose.onNodeWithTag("theme-dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertRole("dialog-title", scheme.onSurface, scheme.surfaceContainerHigh)
            assertRole("dialog-body", scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
        }
    }

    private fun assertRole(key: String, expected: Color, surface: Color) {
        val foreground =
            checkNotNull(foregrounds[key]) { "Missing actual Material3 content slot: $key" }
        assertEquals("$key must use the Material3 role", expected.toArgb(), foreground.toArgb())
        assertReadable(key, foreground, surface)
    }

    private fun assertReadable(key: String, foreground: Color, surface: Color) {
        val ratio = contrastRatio(foreground.toArgb(), surface.toArgb())
        assertTrue("$key foreground/surface contrast is $ratio, expected >= 4.5", ratio >= 4.5)
    }
}
