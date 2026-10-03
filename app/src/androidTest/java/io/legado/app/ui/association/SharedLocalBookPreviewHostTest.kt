package io.legado.app.ui.association

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.FileSharedLocalBookPreviewRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class SharedLocalBookPreviewHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before
    fun setup() {
        directory =
            File(context.cacheDir, "shared-compose-host-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    private fun launch(file: File, type: String): ActivityScenario<FileAssociationActivity> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        return ActivityScenario.launch(
            Intent(Intent.ACTION_SEND)
                .setType(type)
                .setPackage(context.packageName)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun await(scenario: ActivityScenario<FileAssociationActivity>) {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                        as? ImportLocalBookDialog
                ready =
                    dialog?.model?.state?.value?.let {
                        it.loaded && !it.loading && it.rows.isNotEmpty()
                    } == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Shared-book Compose preview did not load")
    }

    @Test
    fun actualArchivePreviewRecreationKeepsSelectionScrollAndPipelineMetadataWithoutImporting() {
        val archive = File(directory, "batch-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            repeat(30) { index ->
                zip.putNextEntry(ZipEntry("book-$index.txt"))
                zip.write("Chapter one\nOriginal $index\n".repeat(4).toByteArray())
                zip.closeEntry()
            }
        }
        val original = archive.readBytes()
        launch(archive, "application/zip").use { scenario ->
            await(scenario)
            lateinit var pipeline: FileAssociationViewModel
            lateinit var target: String
            scenario.onActivity { activity ->
                pipeline = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                        as ImportLocalBookDialog
                assertEquals(30, dialog.model.state.value.rows.size)
                assertEquals(30, pipeline.selectedLocalBooks.size)
                assertTrue(dialog.arguments == null || dialog.requireArguments().isEmpty)
                target = dialog.model.state.value.rows[20].id
            }
            compose.onNodeWithTag("shared-local-list").performScrollToIndex(20)
            compose.onNodeWithTag("shared-local-row-$target").performClick().assertIsOff()
            compose.waitUntil { pipeline.selectedLocalBooks.size == 29 }
            val metadata = pipeline.localBookBatch.value!!.map { checkNotNull(it.preview).copy() }
            scenario.recreate()
            await(scenario)
            compose.onNodeWithTag("shared-local-row-$target").assertIsDisplayed().assertIsOff()
            scenario.onActivity { activity ->
                val restored = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                val dialog =
                    activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                        as ImportLocalBookDialog
                assertEquals(
                    1,
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<ImportLocalBookDialog>()
                        .size,
                )
                assertEquals(29, restored.selectedLocalBooks.size)
                assertTrue(dialog.model.scroll().first >= 19)
                assertEquals(metadata, restored.localBookBatch.value!!.map { it.preview })
                assertEquals(
                    metadata.map { FileSharedLocalBookPreviewRepository.id(it.bookUrl) }.size,
                    dialog.model.state.value.rows.size,
                )
                assertFalse(restored.importedLocalBooks.value == true)
                assertFalse(restored.importingLocalBooks.value == true)
            }
            compose.onNodeWithTag("shared-local-cancel").performClick()
        }
        assertArrayEquals(original, archive.readBytes())
    }

    @Test
    fun cancelActualSingleFilePreviewKeepsOriginalContentAndDoesNotCreateBookRecord() {
        val source =
            File(directory, "single-${UUID.randomUUID()}.txt").apply {
                writeText("Chapter one\nUnchanged original\n".repeat(8))
            }
        val original = source.readBytes()
        var stagedPath = ""
        launch(source, "text/plain").use { scenario ->
            await(scenario)
            scenario.onActivity { activity ->
                stagedPath =
                    ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                        .localBookBatch
                        .value!!
                        .single()
                        .preview!!
                        .bookUrl
            }
            compose.onNodeWithTag("shared-local-confirm").assertIsEnabled()
            compose.onNodeWithTag("shared-local-cancel").performClick()
        }
        assertArrayEquals(original, source.readBytes())
        runBlocking(Dispatchers.IO) {
            assertFalse(io.legado.app.data.appDb.bookDao.has(stagedPath))
        }
    }
}
