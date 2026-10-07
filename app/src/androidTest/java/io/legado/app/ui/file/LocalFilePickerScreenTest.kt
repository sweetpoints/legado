package io.legado.app.ui.file

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class LocalFilePickerScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: LocalFilePickerViewModel

    private class Fake : LocalFilePickerRepository {
        val rows =
            listOf(
                LocalFilePickerRow("/root/sub", "sub", true, true),
                LocalFilePickerRow("/root/a.txt", "a.txt", false, true),
                LocalFilePickerRow("/root/b.pdf", "b.pdf", false, false),
            )
        val validations = mutableListOf<String>()
        val creations = mutableListOf<Pair<String, String>>()
        var validateGate: CompletableDeferred<Unit>? = null
        var createGate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun list(config: LocalFilePickerConfig, directory: String) =
            LocalFilePickerSnapshot(
                directory,
                "/root".takeIf { directory != it },
                if (directory == "/root") emptyList()
                else listOf(LocalFilePickerCrumb(directory, "sub")),
                if (directory == "/root") rows else emptyList(),
            )

        override suspend fun create(
            config: LocalFilePickerConfig,
            directory: String,
            name: String,
        ) {
            creations += directory to name
            createGate?.await()
            if (fail) error("failed")
        }

        override suspend fun validate(config: LocalFilePickerConfig, path: String): String {
            validations += path
            validateGate?.await()
            if (fail) error("failed")
            return path
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private fun show(
        repo: Fake,
        dir: Boolean = false,
        saved: SavedStateHandle = SavedStateHandle(),
        owner: Owner? = null,
        result: (String) -> Unit = {},
        close: () -> Unit = {},
    ) {
        compose.runOnIdle {
            model = LocalFilePickerViewModel(repo, saved, LocalFilePickerConfig("/root", dir))
        }
        compose.setContent {
            LegadoComposeTheme {
                if (owner == null)
                    LocalFilePickerRoute(model, "Chooser title", { true }, result, close, {})
                else
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        LocalFilePickerRoute(model, "Chooser title", { true }, result, close, {})
                    }
            }
        }
        compose.waitUntil { model.state.value.loaded }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.stop() }
    }

    @Test
    fun fileSelectionRespectsDisabledRowsAndDeliversExactPathOnce() {
        val repo = Fake()
        val results = mutableListOf<String>()
        var closes = 0
        show(repo, result = { results += it }, close = { closes++ })
        compose.onNodeWithTag("file-picker-title").assertTextEquals("Chooser title")
        compose.onNodeWithTag("file-picker-row-/root/b.pdf").assertIsNotEnabled()
        compose.onNodeWithTag("file-picker-row-/root/a.txt").performClick().assertIsSelected()
        compose.onNodeWithTag("file-picker-confirm").performClick()
        compose.waitUntil { closes == 1 }
        assertEquals(listOf("/root/a.txt"), results)
        assertEquals(listOf("/root/a.txt"), repo.validations)
        compose.runOnIdle {
            model.confirm()
            model.delivered(1)
        }
        compose.waitForIdle()
        assertEquals(1, closes)
        assertEquals(1, results.size)
    }

    @Test
    fun parentAndRootBreadcrumbNavigateAndDirectoryConfirmReturnsCurrentFolder() {
        val repo = Fake()
        val results = mutableListOf<String>()
        show(repo, dir = true, result = { results += it })
        compose.onNodeWithTag("file-picker-row-/root/sub").performClick()
        compose.onNodeWithTag("file-picker-crumb-/root/sub").assertTextEquals("sub")
        compose.onNodeWithTag("file-picker-row-/root").assertTextEquals("..").performClick()
        compose.waitUntil { model.state.value.directory == "/root" }
        compose.onNodeWithTag("file-picker-row-/root/sub").performClick()
        compose.onNodeWithTag("file-picker-root").performClick()
        compose.waitUntil { model.state.value.directory == "/root" }
        compose.onNodeWithTag("file-picker-confirm").performClick()
        compose.waitUntil { results.size == 1 }
        assertEquals("/root", results.single())
    }

    @Test
    fun activeConfirmationDisablesDuplicateSelectionAndCancelUntilValidationFinishes() {
        val repo = Fake().apply { validateGate = CompletableDeferred() }
        var closes = 0
        var results = 0
        show(repo, result = { results++ }, close = { closes++ })
        compose.onNodeWithTag("file-picker-row-/root/a.txt").performClick()
        compose.onNodeWithTag("file-picker-confirm").performClick()
        compose.onNodeWithTag("file-picker-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("file-picker-row-/root/sub").assertIsNotEnabled()
        compose.onNodeWithTag("file-picker-create").assertIsNotEnabled()
        compose.runOnIdle {
            model.confirm()
            model.cancel()
            assertEquals(1, repo.validations.size)
            repo.validateGate!!.complete(Unit)
        }
        compose.waitUntil { closes == 1 }
        assertEquals(1, results)
    }

    @Test
    fun createEditorPreservesSelectionAndCancelDoesNotWriteFileSystem() {
        val repo = Fake()
        show(repo)
        compose.onNodeWithTag("file-picker-create").performClick()
        compose.onNodeWithTag("file-picker-folder-name").assertIsFocused().performTextInput(" New ")
        compose.onNodeWithTag("file-picker-folder-name").performTextInputSelection(TextRange(1, 4))
        compose.runOnIdle {
            assertEquals(1, model.state.value.folderStart)
            assertEquals(4, model.state.value.folderEnd)
        }
        compose.onNodeWithTag("file-picker-create-cancel").performClick()
        assertTrue(repo.creations.isEmpty())
        compose.onNodeWithTag("file-picker-create").performClick()
        compose.onNodeWithTag("file-picker-folder-name").assertTextContains(" New ")
    }

    @Test
    fun createConfirmationTrimsNameAndDisablesDuplicateWhileWriting() {
        val repo = Fake().apply { createGate = CompletableDeferred() }
        show(repo)
        compose.onNodeWithTag("file-picker-create").performClick()
        compose.onNodeWithTag("file-picker-folder-name").performTextInput(" New ")
        compose.onNodeWithTag("file-picker-create-confirm").performClick()
        compose.onNodeWithTag("file-picker-create-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("file-picker-create-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("file-picker-folder-name").assertIsNotEnabled()
        compose.runOnIdle {
            model.create()
            assertEquals(listOf("/root" to "New"), repo.creations)
            repo.createGate!!.complete(Unit)
        }
        compose.waitUntil { !model.state.value.creating }
        assertEquals(1, repo.creations.size)
    }

    @Test
    fun failedValidationShowsErrorAndRetryPreservesSelectedFile() {
        val repo = Fake()
        show(repo)
        compose.onNodeWithTag("file-picker-row-/root/a.txt").performClick()
        compose.runOnIdle { repo.fail = true }
        compose.onNodeWithTag("file-picker-confirm").performClick()
        compose.onNodeWithTag("file-picker-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }
        compose.onNodeWithTag("file-picker-retry").performClick()
        compose.onNodeWithTag("file-picker-row-/root/a.txt").assertIsSelected()
    }

    @Test
    fun pendingResultWaitsForResumedAndNativeExceptionCannotRepeatDelivery() {
        val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        val repo = Fake()
        var results = 0
        var closes = 0
        show(
            repo,
            owner = owner,
            result = {
                results++
                error("native callback")
            },
            close = { closes++ },
        )
        compose.onNodeWithTag("file-picker-row-/root/a.txt").performClick()
        compose.onNodeWithTag("file-picker-confirm").performClick()
        compose.waitUntil { model.state.value.result != null }
        assertEquals(0, results)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { closes == 1 }
        assertEquals(1, results)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, results)
        assertEquals(1, closes)
    }

    @Test
    fun cancelClosesOnceWithoutValidationOrCreation() {
        val repo = Fake()
        var closes = 0
        show(repo, close = { closes++ })
        compose.runOnIdle {
            model.cancel()
            model.cancel()
        }
        compose.waitUntil { closes == 1 }
        assertTrue(repo.validations.isEmpty())
        assertTrue(repo.creations.isEmpty())
    }

    @Test
    fun typedIssuesRenderChineseResourcesWithoutMatchingEnglishExceptionMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config =
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            }
        val localized = context.createConfigurationContext(config)
        val issue = mutableStateOf(LocalFilePickerIssue.FileRequired)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalResources provides localized.resources,
            ) {
                LegadoComposeTheme {
                    LocalFilePickerScreen(
                        LocalFilePickerState(
                            "/root",
                            loaded = true,
                            loading = false,
                            issue = issue.value,
                        ),
                        "Files",
                        "/root",
                        {},
                        {},
                        {},
                        {},
                        {},
                        { _, _, _ -> },
                        {},
                        {},
                        { 0 to 0 },
                        { _, _, _ -> },
                    )
                }
            }
        }
        val expected =
            linkedMapOf(
                LocalFilePickerIssue.FileRequired to "请选择文件",
                LocalFilePickerIssue.FolderNameRequired to "文件夹名不能为空",
                LocalFilePickerIssue.DirectoryMissing to "文件夹不存在",
                LocalFilePickerIssue.DirectoryUnreadable to "无法读取文件夹",
                LocalFilePickerIssue.OutsideRoot to "路径超出所选文件夹",
                LocalFilePickerIssue.InvalidFolderName to "非法文件夹名",
                LocalFilePickerIssue.CreateFailed to "无法创建文件夹",
                LocalFilePickerIssue.SelectionInvalid to "所选文件不存在或不允许选择",
            )
        expected.forEach { (value, text) ->
            compose.runOnIdle { issue.value = value }
            compose.onNodeWithTag("file-picker-error").assertTextEquals(text)
        }
    }

    @Test
    fun typedCreateIssueIsLocalizedInsideChineseFolderEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config =
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            }
        val localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalResources provides localized.resources,
            ) {
                LegadoComposeTheme {
                    LocalFilePickerScreen(
                        LocalFilePickerState(
                            "/root",
                            loaded = true,
                            loading = false,
                            creating = true,
                            issue = LocalFilePickerIssue.FolderNameRequired,
                        ),
                        "Files",
                        "/root",
                        {},
                        {},
                        {},
                        {},
                        {},
                        { _, _, _ -> },
                        {},
                        {},
                        { 0 to 0 },
                        { _, _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("file-picker-create-error").assertTextEquals("文件夹名不能为空")
        compose.onNodeWithTag("file-picker-folder-name").assertTextContains("文件夹名")
        compose.onNodeWithTag("file-picker-error").assertDoesNotExist()
    }
}
