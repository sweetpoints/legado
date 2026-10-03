package io.legado.app.ui.font

import android.graphics.Typeface
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.AppFontSelectionRepository
import io.legado.app.data.repository.FontEntry
import io.legado.app.data.repository.FontLoadResult
import io.legado.app.data.repository.FontSelectionRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FontSelectScreenTest {
    @get:Rule val compose = createComposeRule()
    private val entry = FontEntry("/font/A.ttf", "file:///font/A.ttf", "A.ttf", true)

    private fun show(
        state: FontSelectUiState,
        selectedPath: String = "",
        onSelect: (String) -> Unit = {},
        onDefault: () -> Unit = {},
        onFolder: () -> Unit = {},
        onImport: () -> Unit = {},
        onSystem: (Int) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            LegadoComposeTheme {
                FontSelectScreen(
                    state,
                    selectedPath,
                    onSelect,
                    onDefault,
                    onFolder,
                    onImport,
                    {},
                    onSystem,
                    onCancel,
                    {},
                    { _, label, modifier -> Text(label, modifier) },
                )
            }
        }
    }

    @Test
    fun selectedRowUsesSelectionSemanticsAndReturnsExactPath() {
        var selected: String? = null
        show(FontSelectUiState(listOf(entry), loading = false), entry.path, { selected = it })
        compose.onNodeWithTag("font-entry-${entry.path}").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(entry.path, selected) }
    }

    @Test
    fun menuActionsEmitDefaultFolderAndImportCallbacks() {
        var defaults = 0
        var folders = 0
        var imports = 0
        show(
            FontSelectUiState(loading = false),
            onDefault = { defaults++ },
            onFolder = { folders++ },
            onImport = { imports++ },
        )
        listOf("font-default", "font-folder", "font-import").forEach {
            compose.onNodeWithTag("font-actions").performClick()
            compose.onNodeWithTag(it).performClick()
        }
        compose.runOnIdle {
            assertEquals(1, defaults)
            assertEquals(1, folders)
            assertEquals(1, imports)
        }
    }

    @Test
    fun busyImportDisablesListAndMutatingMenuActions() {
        show(FontSelectUiState(listOf(entry), loading = false, importing = true))
        compose.onNodeWithTag("font-entry-${entry.path}").assertIsNotEnabled()
        compose.onNodeWithTag("font-actions").performClick()
        compose.onNodeWithTag("font-default").assertIsNotEnabled()
        compose.onNodeWithTag("font-folder").assertIsNotEnabled()
        compose.onNodeWithTag("font-import").assertIsNotEnabled()
    }

    @Test
    fun systemFontChoicesEmitExactIndex() {
        var index: Int? = null
        show(FontSelectUiState(loading = false, systemPicker = true), onSystem = { index = it })
        compose.onNodeWithTag("font-system-2").performClick()
        compose.runOnIdle { assertEquals(2, index) }
    }

    @Test
    fun invalidTypefacePreviewFallsBackToDefaultOffTheUiThread() {
        val repository =
            AppFontSelectionRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val typeface = runBlocking { repository.preview(entry.copy(path = "/missing-font.ttf")) }
        assertSame(Typeface.DEFAULT, typeface)
    }

    @Test
    fun selectionEffectDeliversOnMainAndAcknowledgesOnce() {
        lateinit var model: FontSelectViewModel
        val delivered = mutableListOf<String>()
        var onMain = false
        compose.runOnIdle {
            model =
                FontSelectViewModel(
                    object : FontSelectionRepository {
                        override fun storedFolder(): String? = null

                        override fun storeFolder(folder: String) = Unit

                        override suspend fun load(folder: String?) = FontLoadResult(emptyList())

                        override suspend fun importFont(uri: String) = Unit

                        override suspend fun preview(entry: FontEntry): Typeface? = null

                        override fun selectSystemTypeface(index: Int) = Unit
                    },
                    SavedStateHandle(),
                )
        }
        compose.setContent {
            LegadoComposeTheme {
                FontSelectRoute(
                    model,
                    "",
                    { model.defaultFont(false) },
                    { false },
                    {},
                    {
                        onMain = Looper.myLooper() == Looper.getMainLooper()
                        delivered += it
                        true
                    },
                    {},
                )
            }
        }
        compose.runOnIdle { model.defaultFont(false) }
        compose.waitUntil { delivered.size == 1 }
        compose.runOnIdle {
            assertTrue(onMain)
            assertEquals(listOf(""), delivered)
            assertTrue(model.state.value.finished)
            model.defaultFont(false)
        }
        compose.runOnIdle { assertEquals(1, delivered.size) }
    }
}
