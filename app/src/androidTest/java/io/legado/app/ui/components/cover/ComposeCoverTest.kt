package io.legado.app.ui.components.cover

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.data.image.*
import io.legado.app.data.repository.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposeCoverTest {
    @get:Rule val compose = createComposeRule()

    private fun bitmap(color: Int) =
        Bitmap.createBitmap(90, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun config() =
        CoverConfiguration(
            bitmap(android.graphics.Color.WHITE),
            backgroundColor = android.graphics.Color.WHITE,
            accentColor = android.graphics.Color.BLACK,
        )

    private fun centerPixel(tag: String = "cover"): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun blackPixels(): Int {
        val pixels = compose.onNodeWithTag("cover").captureToImage().toPixelMap()
        return (0 until pixels.height).sumOf { y ->
            (0 until pixels.width).count { x ->
                val pixel = pixels[x, y]
                pixel.alpha > .9f && pixel.red < .1f && pixel.green < .1f && pixel.blue < .1f
            }
        }
    }

    @Test
    fun respectsIncomingCoverSizeAndDisplaysLoadedBitmap() {
        val repo =
            Fake(config()).apply {
                loadImage = {
                    CoverLoadResult(CoverImage.Static(bitmap(android.graphics.Color.RED)), false)
                }
            }
        compose.setContent {
            ComposeCover(
                CoverRequest("red"),
                Modifier.size(72.dp, 96.dp).testTag("cover"),
                repository = repo,
            )
        }
        compose.waitUntil { centerPixel() == Color.Red }
        compose.onNodeWithTag("cover").assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(96.dp)
    }

    @Test
    fun slowRemoteShowsTitleThenSuccessRemovesFallbackAndCallsOnce() {
        val pending = CompletableDeferred<CoverLoadResult>()
        var finished = 0
        val repo = Fake(config()).apply { loadImage = { pending.await() } }
        compose.setContent {
            ComposeCover(
                CoverRequest("remote", "Title", "Author"),
                Modifier.size(90.dp, 120.dp).testTag("cover"),
                onLoadFinish = { finished++ },
                repository = repo,
            )
        }
        compose.waitUntil(timeoutMillis = 5000) { blackPixels() > 0 }
        pending.complete(
            CoverLoadResult(CoverImage.Static(bitmap(android.graphics.Color.GREEN)), false)
        )
        compose.waitUntil { finished == 1 && centerPixel() == Color.Green }
        assertEquals(0, blackPixels())
    }

    @Test
    fun failureAndDefaultKeepGeneratedTitleAndHonorDrawNameToggle() {
        val repo =
            Fake(config()).apply {
                loadImage = {
                    CoverLoadResult(CoverImage.Static(configurations.value!!.defaultBitmap), true)
                }
            }
        compose.setContent {
            ComposeCover(
                CoverRequest(null, "Title", "Author"),
                Modifier.size(90.dp, 120.dp).testTag("cover"),
                repository = repo,
            )
        }
        compose.waitUntil { blackPixels() > 0 }
        compose.runOnIdle {
            repo.configurations.value = repo.configurations.value!!.copy(drawName = false)
        }
        compose.waitUntil { blackPixels() == 0 }
    }

    @Test
    fun replacingRequestDoesNotDisplayLateOldLoad() {
        val pending = CompletableDeferred<CoverLoadResult>()
        val request = mutableStateOf(CoverRequest("old"))
        val repo =
            Fake(config()).apply {
                loadImage = { value ->
                    if (value.path == "old") pending.await()
                    else
                        CoverLoadResult(
                            CoverImage.Static(bitmap(android.graphics.Color.BLUE)),
                            false,
                        )
                }
            }
        compose.setContent {
            ComposeCover(
                request.value,
                Modifier.size(90.dp, 120.dp).testTag("cover"),
                repository = repo,
            )
        }
        compose.waitUntil { repo.loads.contains("old") }
        compose.runOnIdle { request.value = CoverRequest("new") }
        compose.waitUntil { centerPixel() == Color.Blue }
        pending.complete(
            CoverLoadResult(CoverImage.Static(bitmap(android.graphics.Color.RED)), false)
        )
        compose.waitForIdle()
        assertEquals(Color.Blue, centerPixel())
    }

    @Test
    fun groupHasFourSlotsWithMissingSlotTransparentAndCustomCoverOverridesGrid() {
        val custom = mutableStateOf<String?>(null)
        val repo =
            Fake(config()).apply {
                loadImage = { request ->
                    CoverLoadResult(
                        CoverImage.Static(
                            bitmap(
                                when (request.path) {
                                    "1" -> android.graphics.Color.RED
                                    "2" -> android.graphics.Color.GREEN
                                    "3" -> android.graphics.Color.BLUE
                                    else -> android.graphics.Color.YELLOW
                                }
                            )
                        ),
                        false,
                    )
                }
            }
        val books =
            (1..3).map { index ->
                BookshelfBook(
                    "url$index",
                    "source",
                    "$index",
                    "author",
                    "$index",
                    null,
                    0,
                    0,
                    false,
                    0,
                    0,
                    0,
                )
            }
        compose.setContent {
            ComposeGroupCover(
                custom.value,
                books,
                Modifier.size(90.dp, 120.dp).background(Color.Magenta).testTag("group"),
                repository = repo,
            )
        }
        compose.waitUntil { repo.loads.containsAll(listOf("1", "2", "3")) }
        compose.waitUntil {
            val pixels = compose.onNodeWithTag("group").captureToImage().toPixelMap()
            pixels[pixels.width / 4, pixels.height / 4] == Color.Red &&
                pixels[pixels.width * 3 / 4, pixels.height / 4] == Color.Green &&
                pixels[pixels.width / 4, pixels.height * 3 / 4] == Color.Blue
        }
        val pixels = compose.onNodeWithTag("group").captureToImage().toPixelMap()
        assertEquals(Color.Green, pixels[pixels.width * 3 / 4, pixels.height / 4])
        assertEquals(Color.Blue, pixels[pixels.width / 4, pixels.height * 3 / 4])
        assertEquals(Color.Magenta, pixels[pixels.width * 3 / 4, pixels.height * 3 / 4])
        compose.runOnIdle { custom.value = "custom" }
        compose.waitUntil { centerPixel("group") == Color.Yellow }
    }

    @Test
    fun actualGifChangesFramesAndStopsResumesThenReleasesWhenRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("compose-cover-frames-", ".gif", context.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val configuration = config()
        val loaded = runBlocking {
            GlideCoverImageLoader(context)
                .load(CoverRequest(file.absolutePath), configuration, 90, 120)
        }
        val resource = (loaded.image as CoverImage.Animated).resource
        val gif = resource.drawable as GifDrawable
        val shown = mutableStateOf(true)
        val owner = Owner()
        val repo = Fake(configuration).apply { loadImage = { loaded } }
        try {
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown.value)
                        ComposeCover(
                            CoverRequest(file.absolutePath),
                            Modifier.size(90.dp, 120.dp).testTag("cover"),
                            repository = repo,
                        )
                }
            }
            compose.waitUntil { gif.isRunning }
            val first = centerPixel()
            compose.waitUntil(timeoutMillis = 5000) { centerPixel() != first }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle {
                assertFalse(gif.isRunning)
                assertNull(gif.callback)
            }
            val stopped = centerPixel()
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(stopped, centerPixel())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { gif.isRunning }
            val resumed = centerPixel()
            compose.waitUntil(timeoutMillis = 5000) { centerPixel() != resumed }
            compose.runOnIdle { shown.value = false }
            compose.waitUntil { resource.isReleased }
            compose.runOnIdle {
                assertFalse(gif.isRunning)
                assertNull(gif.callback)
            }
        } finally {
            runBlocking { resource.release() }
            file.delete()
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake(configuration: CoverConfiguration) : CoverRepository {
        override val configurations = MutableStateFlow<CoverConfiguration?>(configuration)
        val loads = mutableListOf<String?>()
        var loadImage: suspend (CoverRequest) -> CoverLoadResult = {
            CoverLoadResult(CoverImage.Static(configuration.defaultBitmap), true)
        }
        private val renderer = CoverTitleRenderer()

        override fun refreshConfiguration() {}

        override suspend fun load(
            request: CoverRequest,
            configuration: CoverConfiguration,
            width: Int,
            height: Int,
        ): CoverLoadResult {
            loads += request.path
            return loadImage(request)
        }

        override suspend fun title(
            request: CoverRequest,
            configuration: CoverConfiguration,
            width: Int,
            height: Int,
        ): Bitmap? =
            if (!configuration.drawName || request.name == null) null
            else renderer.render(request, configuration, width, height)
    }
}
