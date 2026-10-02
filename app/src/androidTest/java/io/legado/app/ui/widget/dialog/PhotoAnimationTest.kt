package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.photo.GlidePhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoImage
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoRequest
import io.legado.app.ui.widget.dialog.photo.PhotoRoute
import io.legado.app.ui.widget.dialog.photo.PhotoScreen
import io.legado.app.ui.widget.dialog.photo.PhotoUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhotoAnimationTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("photo-animation-", ".gif", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun centerPixel(): Color {
        val pixels = compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test fun realGifChangesFramesAndStopsResumesWithLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = fixture()
        val image = runBlocking { GlidePhotoImageLoader(context).load(PhotoRequest(file.absolutePath)) }
        assertTrue(image is PhotoImage.Animated)
        image as PhotoImage.Animated
        val gif = image.drawable as GifDrawable
        assertEquals(2, gif.frameCount)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        try {
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LegadoComposeTheme { PhotoScreen(PhotoUiState(isLoading = false, animation = image), {}) }
                }
            }
            compose.waitUntil { gif.isRunning }
            val first = centerPixel()
            compose.waitUntil(timeoutMillis = 5000) { centerPixel() != first }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { assertFalse(gif.isRunning); assertNull(gif.callback) }
            val stoppedFrame = centerPixel()
            // The Compose clock can advance without starting another drawable animation frame.
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(stoppedFrame, centerPixel())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { gif.isRunning }
            val resumedFrame = centerPixel()
            compose.waitUntil(timeoutMillis = 5000) { centerPixel() != resumedFrame }
        } finally {
            runBlocking { image.release() }
            compose.runOnIdle { assertFalse(gif.isRunning); assertNull(gif.callback) }
            file.delete()
        }
    }

    @Test fun removingViewerStopsAnimationBeforeGlideRecyclesFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = fixture()
        val image = runBlocking { GlidePhotoImageLoader(context).load(PhotoRequest(file.absolutePath)) } as PhotoImage.Animated
        val gif = image.drawable as GifDrawable
        val shown = mutableStateOf(true)
        try {
            compose.setContent {
                LegadoComposeTheme {
                    if (shown.value) PhotoRoute(PhotoRequest(file.absolutePath), PhotoImageLoader { image }, {})
                }
            }
            compose.waitUntil { gif.isRunning }
            compose.runOnIdle { shown.value = false }
            compose.waitUntil { image.isReleased }
            compose.runOnIdle { assertFalse(gif.isRunning); assertNull(gif.callback) }
            compose.onNodeWithTag("photo-image").assertDoesNotExist()
        } finally { runBlocking { image.release() }; file.delete() }
    }
}
