package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.image.MangaImageRepository
import io.legado.app.data.image.MangaImageRequest
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MangaImageScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun resource(onClear: () -> Unit = {}): AnimatedDrawableResource {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap =
            Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return AnimatedDrawableResource(BitmapDrawable(context.resources, bitmap), onClear)
    }

    @Test
    fun continuousImageUsesWidthAspectRatioAndLastImageKeepsMinimumHeight() {
        val image = resource()
        val last = mutableStateOf(false)
        compose.setContent {
            LegadoComposeTheme {
                Box(Modifier.width(100.dp)) {
                    MangaImageScreen(
                        state = MangaImageUiState(resource = image, isLoading = false),
                        horizontal = false,
                        isLastImage = last.value,
                        viewportWidth = 100.dp,
                        viewportHeight = 600.dp,
                        colorFilter = MangaColorFilterValues(),
                        isEInk = false,
                        onRetry = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("manga-image-page").assertHeightIsEqualTo(200.dp)
        compose.runOnIdle { last.value = true }
        compose.onNodeWithTag("manga-image-page").assertHeightIsEqualTo(400.dp)
    }

    @Test
    fun removingRouteStopsDrawableCallbackAndReleasesExactlyOnce() {
        var releases = 0
        val image = resource { releases++ }
        val shown = mutableStateOf(true)
        val repository =
            object : MangaImageRepository {
                override suspend fun load(
                    request: MangaImageRequest,
                    onProgress: (Int) -> Unit,
                ): AnimatedDrawableResource = image

                override suspend fun preload(request: MangaImageRequest) = Unit
            }
        compose.setContent {
            LegadoComposeTheme {
                if (shown.value) {
                    MangaImageRoute(
                        request = MangaImageRequest("book", null, "image"),
                        repository = repository,
                        horizontal = true,
                        isLastImage = false,
                        viewportWidth = 100.dp,
                        viewportHeight = 100.dp,
                        colorFilter = MangaColorFilterValues(),
                        isEInk = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = false }
        compose.waitUntil { image.isReleased }
        compose.runOnIdle {
            assertEquals(1, releases)
            assertNull(image.drawable.callback)
        }
    }
}
