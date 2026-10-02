package io.legado.app.ui.font

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import io.legado.app.data.repository.FontEntry
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FontSelectionThemeTest {
    @get:Rule val compose = createComposeRule()
    private fun renderAndSelect(theme: Int) {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), theme)
        val entry = FontEntry("/font/theme.ttf", "file:///font/theme.ttf", "theme.ttf", true)
        var selected: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                LegadoComposeTheme {
                    FontSelectScreen(FontSelectUiState(listOf(entry), loading = false), entry.path,
                        { selected = it }, {}, {}, {}, {}, {}, {}, {},
                        { _, label, modifier -> Text(label, modifier) })
                }
            }
        }
        compose.onNodeWithTag("font-entry-${entry.path}").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(entry.path, selected) }
    }
    @Test fun fontRowRendersAndSelectsWithLightContext() = renderAndSelect(R.style.AppTheme_Light)
    @Test fun fontRowRendersAndSelectsWithDarkContext() = renderAndSelect(R.style.AppTheme_Dark)
}
