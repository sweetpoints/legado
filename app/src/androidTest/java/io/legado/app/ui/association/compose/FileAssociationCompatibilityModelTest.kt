package io.legado.app.ui.association.compose

import android.app.Application
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.association.AssociationBookPreview
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.FileAssociationSessionRepository
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAssociationCompatibilityModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun originalChildDtoPreservesFullMetadataAndSelectionWithoutSavingUriOrJson() = runBlocking {
        val sessions = FileAssociationSessionRepository(application)
        val ticket =
            sessions.create(
                AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedUri)
            )
        val source =
            File(application.cacheDir, "compatibility-${UUID.randomUUID()}.txt").apply {
                writeText("正文")
            }
        val original =
            Book(
                bookUrl = source.path,
                name = "Full metadata",
                author = "Author",
                intro = "large".repeat(50_000),
                customCoverUrl = "custom cover",
                durChapterPos = 39,
            )
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(
                revision = initial.revision + 1,
                phase = AssociationPhase.Preview,
                previews =
                    listOf(
                        AssociationBookPreview(
                            "preview",
                            Uri.fromFile(source).toString(),
                            source.name,
                            GSON.toJson(original),
                        )
                    ),
                selectedIds = listOf("preview"),
            ),
        )
        val saved = SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to ticket))
        val projected = AtomicReference<List<ImportBook>?>()
        val observer = Observer<List<ImportBook>?> { projected.set(it) }
        lateinit var model: FileAssociationCompatibilityModel
        instrumentation.runOnMainSync {
            model = FileAssociationCompatibilityModel(application, saved)
            model.localBookBatch.observeForever(observer)
        }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (projected.get().isNullOrEmpty()) delay(10) }
            }
            assertEquals(original, projected.get()!!.single().preview)
            instrumentation.runOnMainSync {
                assertEquals(setOf(Uri.fromFile(source)), model.selectedLocalBooks)
                assertEquals(1, model.pendingLocalBooks.size)
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), saved.keys())
                model.updateLocalSelection(emptyList())
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (model.state.value.session?.selectedIds?.isNotEmpty() != false) delay(10)
                }
            }
            instrumentation.runOnMainSync {
                assertTrue(model.selectedLocalBooks.isEmpty())
                assertTrue(model.pendingLocalBooks.isEmpty())
                assertFalse(model.choosingLocalBookDirectory)
            }
        } finally {
            instrumentation.runOnMainSync {
                model.localBookBatch.removeObserver(observer)
                ViewModelStore().apply {
                    put("model", model)
                    clear()
                }
            }
            sessions.release(ticket)
            source.delete()
        }
    }
}
