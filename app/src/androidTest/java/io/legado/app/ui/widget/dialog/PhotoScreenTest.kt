package io.legado.app.ui.widget.dialog

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.photo.PhotoImage
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoRequest
import io.legado.app.ui.widget.dialog.photo.PhotoRoute
import io.legado.app.ui.widget.dialog.photo.PhotoScreen
import io.legado.app.ui.widget.dialog.photo.PhotoUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun bitmap() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    @Test fun singleTapClosesOnlyAfterDoubleTapDecision() {
        var closes = 0
        val image = bitmap()
        compose.setContent { LegadoComposeTheme { PhotoScreen(PhotoUiState(image, false), { closes++ }) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("photo-viewer").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun doubleTapZoomsWithoutClosingAndSecondDoubleTapRestoresFit() {
        var closes = 0
        val image = bitmap()
        compose.setContent { LegadoComposeTheme { PhotoScreen(PhotoUiState(image, false), { closes++ }) } }
        compose.onNodeWithTag("photo-viewer").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2.5×"))
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("photo-viewer").performTouchInput { advanceEventTime(500); doubleClick(center) }
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0×"))
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun dragGestureDoesNotDismiss() {
        var closes = 0
        val image = bitmap()
        compose.setContent { LegadoComposeTheme { PhotoScreen(PhotoUiState(image, false), { closes++ }) } }
        compose.onNodeWithTag("photo-viewer").performTouchInput {
            doubleClick(center)
            advanceEventTime(500)
            swipe(center, center + Offset(100f, 0f), 200)
        }
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun pinchZoomDoesNotBecomeSingleTap() {
        var closes = 0
        val image = bitmap()
        compose.setContent { LegadoComposeTheme { PhotoScreen(PhotoUiState(image, false), { closes++ }) } }
        compose.onNodeWithTag("photo-viewer").performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveTo(0, center - Offset(80f, 0f))
            moveTo(1, center + Offset(80f, 0f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher("zoomed") {
            it.config[SemanticsProperties.StateDescription] != "1.0×"
        })
    }

    @Test fun routeCancelsStaleLoadWhenRequestChanges() {
        val request = mutableStateOf(PhotoRequest("first", "origin", true))
        val first = CompletableDeferred<Bitmap>()
        val second = bitmap().apply { eraseColor(android.graphics.Color.GREEN) }
        val requests = mutableListOf<PhotoRequest>()
        val loader = PhotoImageLoader {
            requests += it
            PhotoImage.Static(if (it.src == "first") withContext(NonCancellable) { first.await() } else second)
        }
        compose.setContent { LegadoComposeTheme { PhotoRoute(request.value, loader, {}) } }
        compose.waitUntil { requests.size == 1 }
        compose.runOnIdle { request.value = PhotoRequest("second") }
        compose.waitUntil { requests.size == 2 }
        compose.onNodeWithTag("photo-image").assertExists()
        compose.onNodeWithTag("photo-viewer").performTouchInput { doubleClick(center) }
        compose.runOnIdle { first.complete(bitmap().apply { eraseColor(android.graphics.Color.RED) }) }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-image").assertExists()
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2.5×"))
        val pixels = compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
        assertEquals(Color.Green, pixels[pixels.width / 2, pixels.height / 2])
        compose.runOnIdle { assertEquals(listOf(PhotoRequest("first", "origin", true), PhotoRequest("second")), requests) }
    }

    @Test fun savedZoomSurvivesRecreationAndTheSameImageReload() {
        val restoration = StateRestorationTester(compose)
        val state = mutableStateOf(PhotoUiState(bitmap(), false))
        restoration.setContent { LegadoComposeTheme { PhotoScreen(state.value, {}, imageKey = "same-src") } }
        compose.onNodeWithTag("photo-viewer").performTouchInput { doubleClick(center) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2.5×"))
        compose.runOnIdle { state.value = PhotoUiState() }
        compose.onNodeWithTag("photo-loading").assertExists()
        compose.runOnIdle { state.value = PhotoUiState(bitmap(), false) }
        compose.onNodeWithTag("photo-viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2.5×"))
    }

    @Test fun argumentsRetainExistingConstructorContract() {
        val dialog = PhotoDialog("src", "origin", true)
        assertEquals("src", dialog.arguments?.getString("src"))
        assertEquals("origin", dialog.arguments?.getString("sourceOrigin"))
        assertTrue(dialog.arguments?.getBoolean("isBook") == true)
    }
}
