package io.legado.app.ui.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class SourceWebLoginScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun titleBackAndCheckKeepTheirOriginalActionsAndCheckingDisablesDuplicateCheck() {
        var state by mutableStateOf(SourceWebLoginState(progress = 35))
        var checks = 0
        var backs = 0
        compose.setContent {
            LegadoComposeTheme {
                SourceWebLoginScreen(
                    "Exact source",
                    state,
                    true,
                    remember { SnackbarHostState() },
                    { backs++ },
                    {
                        checks++
                        state = state.copy(checking = true)
                    },
                ) {
                    Box(Modifier.fillMaxSize().testTag("native-content"))
                }
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.login_source, "Exact source"))
            .assertIsDisplayed()
        compose.onNodeWithTag("source-web-login-progress").assertIsDisplayed()
        compose.onNodeWithTag("source-web-login-check").performClick()
        compose.onNodeWithTag("source-web-login-check").assertIsNotEnabled()
        compose.onNodeWithTag("source-web-login-back").performClick()
        assertEquals(1, checks)
        assertEquals(1, backs)
    }

    @Test
    fun progressHidesAtCompletionWhileContentKeepsItsCompositionAndLifecycleOwner() {
        var state by mutableStateOf(SourceWebLoginState(progress = 12))
        var creates = 0
        compose.setContent {
            LegadoComposeTheme {
                SourceWebLoginScreen(
                    "Feed",
                    state,
                    true,
                    remember { SnackbarHostState() },
                    {},
                    {},
                ) {
                    remember { creates++ }
                    Box(Modifier.fillMaxSize().testTag("native-content"))
                }
            }
        }
        compose.onNodeWithTag("source-web-login-progress").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(progress = 100) }
        compose.onNodeWithTag("source-web-login-progress").assertDoesNotExist()
        compose.onNodeWithTag("native-content").assertIsDisplayed()
        assertEquals(1, creates)
    }

    @Test
    fun inactiveDestinationDisablesNativeActionsWithoutRemovingBrowserContent() {
        compose.setContent {
            LegadoComposeTheme {
                SourceWebLoginScreen(
                    "Feed",
                    SourceWebLoginState(),
                    false,
                    remember { SnackbarHostState() },
                    { fail("Inactive") },
                    { fail("Inactive") },
                ) {
                    Box(Modifier.fillMaxSize().testTag("native-content"))
                }
            }
        }
        compose.onNodeWithTag("source-web-login-check").assertIsNotEnabled()
        compose.onNodeWithTag("source-web-login-back").assertIsNotEnabled()
        compose.onNodeWithTag("native-content").assertIsDisplayed()
    }

    @Test
    fun runtimeSurfaceIsOpaqueAndNativeContentStartsBelowComposeToolbar() {
        var expected = Color.Unspecified
        compose.setContent {
            LegadoComposeTheme {
                expected = MaterialTheme.colorScheme.surface
                SourceWebLoginScreen(
                    "Feed",
                    SourceWebLoginState(progress = 100),
                    true,
                    remember { SnackbarHostState() },
                    {},
                    {},
                ) {
                    Box(Modifier.fillMaxSize().testTag("native-content"))
                }
            }
        }
        val root = compose.onNodeWithTag("source-web-login-root").captureToImage().toPixelMap()
        assertEquals(expected.toArgb(), root[1, 1].toArgb())
        val toolbar =
            compose.onNodeWithTag("source-web-login-check").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot
        assertTrue(content.top >= toolbar.bottom)
    }
}
