package io.legado.app.ui.book.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.image.MangaImageRepository
import io.legado.app.data.image.MangaImageRequest
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MangaViewportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val items =
        listOf(
            MangaReaderItem.Boundary(0, -1, "first", true),
            MangaReaderItem.Boundary(1, -1, "second", true),
            MangaReaderItem.Boundary(2, -1, "third", true),
        )
    private val repository =
        object : MangaImageRepository {
            override suspend fun load(
                request: MangaImageRequest,
                onProgress: (Int) -> Unit,
            ): AnimatedDrawableResource = error("No images in volume-boundary fixture")

            override suspend fun preload(request: MangaImageRequest) = Unit
        }

    @Test
    fun horizontalRtlPageCommandKeepsLogicalChapterOrder() {
        val command = mutableStateOf<MangaScrollCommand?>(null)
        var chapter = -1
        var handled = 0L
        compose.setContent {
            LegadoComposeTheme {
                Box(Modifier.size(300.dp, 500.dp)) {
                    MangaViewportScreen(
                        sessionKey = "viewport-test",
                        items = items,
                        bookUrl = "book",
                        sourceOrigin = null,
                        repository = repository,
                        options =
                            MangaViewportOptions(
                                horizontal = true,
                                rightToLeft = true,
                                disablePageAnimation = true,
                            ),
                        colorFilter = MangaColorFilterValues(),
                        anchorIndex = 0,
                        command = command.value,
                        readerActive = false,
                        onCurrentItem = { chapter = it.chapterIndex },
                        onCommandHandled = { handled = it },
                        onMenu = {},
                        onPageTap = {},
                        onLongPress = {},
                    )
                }
            }
        }
        compose.waitUntil { chapter == 0 }
        compose.runOnIdle { command.value = MangaScrollCommand.Page(1, 1) }
        compose.waitUntil { chapter == 1 && handled == 1L }
        compose.runOnIdle { command.value = MangaScrollCommand.Page(2, -1) }
        compose.waitUntil { chapter == 0 && handled == 2L }
    }

    @Test
    fun delayedContentRestoresRequestedAnchorWithExplicitJump() {
        val loaded = mutableStateOf<List<MangaReaderItem>>(emptyList())
        val command = mutableStateOf<MangaScrollCommand?>(null)
        var chapter = -1
        var handled = 0L
        compose.setContent {
            LegadoComposeTheme {
                Box(Modifier.size(300.dp, 500.dp)) {
                    MangaViewportScreen(
                        sessionKey = "delayed-restore-test",
                        items = loaded.value,
                        bookUrl = "book",
                        sourceOrigin = null,
                        repository = repository,
                        options =
                            MangaViewportOptions(horizontal = true, disablePageAnimation = true),
                        colorFilter = MangaColorFilterValues(),
                        anchorIndex = if (loaded.value.isEmpty()) 0 else 2,
                        command = command.value,
                        readerActive = false,
                        onCurrentItem = { chapter = it.chapterIndex },
                        onCommandHandled = { handled = it },
                        onMenu = {},
                        onPageTap = {},
                        onLongPress = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            loaded.value = items
            command.value = MangaScrollCommand.Jump(42, 2)
        }
        compose.waitUntil { chapter == 2 && handled == 42L }
    }

    @Test
    fun onlyOriginalCenterAndBottomCornerTapRectanglesTriggerActions() {
        var menu = 0
        var direction = 0
        compose.setContent {
            LegadoComposeTheme {
                Box(Modifier.size(300.dp, 500.dp)) {
                    MangaViewportScreen(
                        sessionKey = "tap-test",
                        items = items,
                        bookUrl = "book",
                        sourceOrigin = null,
                        repository = repository,
                        options = MangaViewportOptions(horizontal = true, rightToLeft = true),
                        colorFilter = MangaColorFilterValues(),
                        anchorIndex = 0,
                        command = null,
                        readerActive = false,
                        onCurrentItem = {},
                        onCommandHandled = {},
                        onMenu = { menu++ },
                        onPageTap = { direction = it },
                        onLongPress = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("manga-viewport").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, menu) }
        compose.onNodeWithTag("manga-viewport").performTouchInput {
            click(Offset(width * .1f, height * .9f))
        }
        compose.runOnIdle { assertEquals(1, direction) }
        compose.onNodeWithTag("manga-viewport").performTouchInput {
            click(Offset(width * .1f, height * .1f))
        }
        compose.runOnIdle {
            assertEquals(1, menu)
            assertEquals(1, direction)
        }
    }
}
