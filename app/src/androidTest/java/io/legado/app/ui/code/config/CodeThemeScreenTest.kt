package io.legado.app.ui.code.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.preferences.CodeThemePreferences
import io.legado.app.data.preferences.CodeThemeSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CodeThemeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allEightChoicesAreReachableInSmallWindowAndExclusive() {
        val state = mutableStateOf(CodeThemeUiState(loading = false))
        compose.setContent {
            LegadoComposeTheme {
                CodeThemeScreen(
                    state.value,
                    {},
                    { state.value = state.value.copy(light = it) },
                    {},
                    Modifier.heightIn(max = 240.dp),
                )
            }
        }
        (0..7).forEach { index ->
            compose
                .onNodeWithTag("code-theme-$index")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
        }
        compose.onNodeWithTag("code-theme-0").performScrollTo().assertIsNotSelected()
    }

    @Test
    fun automaticSwitchChangesPreviewedDarkSlot() {
        val model = CodeThemeViewModel(Fake(CodeThemeSnapshot(false, 1, 6)), SavedStateHandle())
        model.setSystemDark(true)
        val previews = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { CodeThemeRoute(model, { previews += it }) } }
        compose.waitUntil { previews.lastOrNull() == 1 }
        compose.onNodeWithTag("code-theme-auto").performClick().assertIsOn()
        compose.waitUntil { previews.lastOrNull() == 6 }
        compose.onNodeWithTag("code-theme-6").performScrollTo().assertIsSelected()
    }

    @Test
    fun choosingThemeUpdatesPreviewAndCorrespondingPreference() {
        val prefs = Fake(CodeThemeSnapshot(true, 1, 2))
        val model = CodeThemeViewModel(prefs, SavedStateHandle())
        model.setSystemDark(true)
        val previews = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { CodeThemeRoute(model, { previews += it }) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("code-theme-5").performScrollTo().performClick()
        compose.waitUntil { previews.lastOrNull() == 5 }
        compose.runOnIdle { assertEquals(listOf(true to 5), prefs.themes) }
    }

    @Test
    fun resumeReappliesThemeToRecreatedHostWithoutSaving() {
        val prefs = Fake(CodeThemeSnapshot(light = 3))
        val model = CodeThemeViewModel(prefs, SavedStateHandle())
        val previews = mutableListOf<Int>()
        lateinit var owner: Owner
        compose.setContent {
            owner = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { CodeThemeRoute(model, { previews += it }) }
            }
        }
        compose.waitUntil { previews.size == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { previews.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf(3, 3), previews)
            assertTrue(prefs.themes.isEmpty())
        }
    }

    @Test
    fun loadingDisablesSelectionAndAutomaticSwitch() {
        compose.setContent {
            LegadoComposeTheme { CodeThemeScreen(CodeThemeUiState(), {}, {}, {}) }
        }
        compose.onNodeWithTag("code-theme-auto").assertIsNotEnabled()
        compose.onNodeWithTag("code-theme-0").performScrollTo().assertIsNotEnabled()
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake(private val snapshot: CodeThemeSnapshot = CodeThemeSnapshot()) :
        CodeThemePreferences {
        val themes = mutableListOf<Pair<Boolean, Int>>()

        override suspend fun load() = snapshot

        override fun saveAutomatic(value: Boolean) {}

        override fun saveTheme(dark: Boolean, index: Int) {
            themes += dark to index
        }
    }
}
