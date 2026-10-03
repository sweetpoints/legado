package io.legado.app.ui.file

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.about.AboutActivity
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class LocalFilePickerRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: File

    @Before
    fun setup() {
        root =
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "file-picker-${UUID.randomUUID()}",
                )
                .apply { mkdirs() }
        PickerCallbackTestFragment.results.clear()
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun await(
        scenario: ActivityScenario<AboutActivity>,
        check: (LocalFilePickerState) -> Boolean = { it.loaded },
    ) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val parent = activity.supportFragmentManager.findFragmentByTag("picker-parent")
                val dialog =
                    parent?.childFragmentManager?.findFragmentByTag(FilePickerDialog.tag)
                        as? FilePickerDialog
                ready = dialog?.model?.state?.value?.let(check) == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("File picker did not load/restore")
    }

    private fun show(
        scenario: ActivityScenario<AboutActivity>,
        mode: Int = HandleFileContract.FILE,
    ) {
        scenario.onActivity { activity ->
            val parent = PickerCallbackTestFragment()
            activity.supportFragmentManager
                .beginTransaction()
                .add(parent, "picker-parent")
                .commitNow()
            FilePickerDialog.show(
                parent.childFragmentManager,
                mode,
                "Files",
                root.path,
                allowExtensions = arrayOf("txt"),
            )
        }
        await(scenario)
    }

    @Test
    fun realRecreationKeepsDirectorySelectionAndScrollAndUsesExistingPublicShowApi() {
        val directory = File(root, "sub").apply { mkdir() }
        val files =
            (0..45).map { File(directory, "book%02d.txt".format(it)).apply { writeText("Text") } }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            show(scenario)
            compose.onNodeWithTag("file-picker-row-${directory.path}").performClick()
            await(scenario) { it.loaded && it.directory == directory.path }
            compose.onNodeWithTag("file-picker-list").performScrollToIndex(21)
            compose.onNodeWithTag("file-picker-row-${files[20].path}").performClick()
            scenario.recreate()
            await(scenario) { it.loaded && it.directory == directory.path }
            compose
                .onNodeWithTag("file-picker-row-${files[20].path}")
                .assertIsDisplayed()
                .assertIsSelected()
            scenario.onActivity { activity ->
                val parent = activity.supportFragmentManager.findFragmentByTag("picker-parent")!!
                assertEquals(
                    1,
                    parent.childFragmentManager.fragments.filterIsInstance<FilePickerDialog>().size,
                )
                val dialog =
                    parent.childFragmentManager.findFragmentByTag(FilePickerDialog.tag)
                        as FilePickerDialog
                assertTrue(dialog.model.scroll(directory.path).first >= 20)
                assertTrue(
                    dialog
                        .requireArguments()
                        .getStringArray("allowExtensions")!!
                        .contentEquals(arrayOf("txt"))
                )
            }
        }
        assertTrue(PickerCallbackTestFragment.results.isEmpty())
    }

    @Test
    fun folderEditorRecreationKeepsSelectionAndCreatesRealFolderOnlyAfterConfirm() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            show(scenario, HandleFileContract.DIR)
            compose.onNodeWithTag("file-picker-create").performClick()
            compose.onNodeWithTag("file-picker-folder-name").performTextInput(" New folder ")
            compose
                .onNodeWithTag("file-picker-folder-name")
                .performTextInputSelection(TextRange(2, 5))
            scenario.recreate()
            await(scenario) { it.loaded && it.creating }
            compose
                .onNodeWithTag("file-picker-folder-name")
                .assertTextContains(" New folder ")
                .assertIsFocused()
            assertFalse(File(root, "New folder").exists())
            scenario.onActivity { activity ->
                val parent = activity.supportFragmentManager.findFragmentByTag("picker-parent")!!
                val dialog =
                    parent.childFragmentManager.findFragmentByTag(FilePickerDialog.tag)
                        as FilePickerDialog
                assertEquals(2, dialog.model.state.value.folderStart)
                assertEquals(5, dialog.model.state.value.folderEnd)
            }
            compose.onNodeWithTag("file-picker-create-confirm").performClick()
            await(scenario) {
                it.loaded && !it.creating && it.rows.any { row -> row.name == "New folder" }
            }
            assertTrue(File(root, "New folder").isDirectory)
        }
        assertTrue(PickerCallbackTestFragment.results.isEmpty())
    }

    @Test
    fun nativeParentCallbackReceivesExactFileUriOnceAndDismissFinishesHost() {
        val file = File(root, "chosen.txt").apply { writeText("Chosen") }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            show(scenario)
            compose.onNodeWithTag("file-picker-row-${file.path}").performClick()
            compose.onNodeWithTag("file-picker-confirm").performClick()
            compose.waitUntil(10000) { PickerCallbackTestFragment.results.size == 1 }
            assertEquals(
                android.net.Uri.fromFile(file),
                PickerCallbackTestFragment.results.single().data,
            )
            val end = SystemClock.uptimeMillis() + 10000
            while (
                scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                    SystemClock.uptimeMillis() < end
            ) SystemClock.sleep(25)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            assertEquals(1, PickerCallbackTestFragment.results.size)
        }
    }
}

class PickerCallbackTestFragment : Fragment(), FilePickerDialog.CallBack {
    override fun onResult(data: Intent) {
        results += data
    }

    companion object {
        val results = java.util.concurrent.CopyOnWriteArrayList<Intent>()
    }
}
