package io.legado.app.ui.association.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.association.AssociationBookPreview
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionRepository
import io.legado.app.data.association.AssociationStagingResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssociationImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val input =
        AssociationInput(
            AssociationHostKind.File,
            AssociationInputKind.View,
            listOf("content://provider/" + "large".repeat(100_000)),
        )
    private val preview =
        AssociationBookPreview("book", "file:///private/book.txt", "book.txt", "complete JSON")

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun launchKeepsOnlyUuidSavedAndRestoresPreviewWithoutReadingIncomingContentAgain() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val sessions = MemorySessions()
            val files =
                Files(
                    AssociationFileInspection(
                        staging = AssociationStagingResult(previews = listOf(preview))
                    )
                )
            val model = model(saved, sessions, files)
            try {
                model.start(input)
                model.start(input.copy(text = "ignored second intent"))
                runCurrent()
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), saved.keys())
                assertEquals(1, sessions.creations)
                assertEquals(input, sessions.current!!.input)
                assertEquals(AssociationPhase.Preview, model.state.value.session!!.phase)
                assertEquals(listOf("book"), model.state.value.session!!.selectedIds)
                assertFalse(model.state.value.busy)
            } finally {
                clear(model)
                runCurrent()
            }
            val restored =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    files,
                )
            try {
                runCurrent()
                assertEquals(listOf(preview), restored.state.value.session!!.previews)
                assertEquals(1, files.calls)
                assertEquals(1, sessions.creations)
            } finally {
                clear(restored)
                runCurrent()
            }
        }

    @Test
    fun unreadablePrivateRestoreDoesNotCreateOrOverwriteAnEmptyImport() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    readFailure = IllegalStateException("corrupt private draft")
                }
            val files =
                Files(
                    AssociationFileInspection(
                        importType = "bookSource",
                        source = "file:///private/source.json",
                    )
                )
            val saved =
                SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "owned-ticket"))
            val model = model(saved, sessions, files)
            try {
                runCurrent()
                assertNotNull(model.state.value.restoreError)
                assertEquals("owned-ticket", model.state.value.ticket)
                model.start(input)
                model.retryInspection()
                runCurrent()
                assertEquals(0, sessions.creations)
                assertEquals(0, sessions.writes)
                assertEquals(0, files.calls)
                assertEquals(
                    "owned-ticket",
                    saved.get<String>(AssociationImportViewModel.TICKET_KEY),
                )
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun stoppedNonCooperativeInspectionCannotPublishLateMetadataOrPersistItsResult() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val files =
                Files(
                        AssociationFileInspection(
                            importType = "bookSource",
                            source = "file:///late.json",
                        )
                    )
                    .apply { resultGate = gate }
            val sessions = MemorySessions()
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input)
                runCurrent()
                assertTrue(model.state.value.busy)
                assertEquals(1, sessions.writes)
                clear(model)
                gate.complete(Unit)
                runCurrent()
                assertEquals(1, sessions.writes)
                assertEquals(AssociationPhase.Loading, sessions.current!!.phase)
                assertTrue(sessions.current!!.effects.isEmpty())
            } finally {
                gate.complete(Unit)
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun failureRetainsPrivateInputAndExplicitRetryPublishesImportDialogOnce() =
        runTest(dispatcher) {
            val sessions = MemorySessions()
            val files =
                Files(
                        AssociationFileInspection(
                            importType = "replaceRule",
                            source = "file:///rules.json",
                        )
                    )
                    .apply { failure = IllegalStateException("provider unavailable") }
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input)
                runCurrent()
                assertEquals(AssociationPhase.Failed, model.state.value.session!!.phase)
                assertEquals(input, sessions.current!!.input)
                files.failure = null
                model.retryInspection()
                runCurrent()
                assertEquals("replaceRule", model.state.value.session!!.effects.single().type)
                assertEquals(2, files.calls)
                assertEquals(1, sessions.creations)
            } finally {
                clear(model)
                runCurrent()
            }
        }

    private fun model(saved: SavedStateHandle, sessions: MemorySessions, files: Files) =
        AssociationImportViewModel(
            saved,
            sessions,
            files,
            object : AssociationOnlineRepository {
                override suspend fun determine(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun readConfig(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun text(url: String): String = error("Unused")
            },
        )

    private fun clear(model: AssociationImportViewModel) {
        ViewModelStore().apply {
            put("model", model)
            clear()
        }
    }

    private class Files(private val result: AssociationFileInspection) : AssociationFileRepository {
        var calls = 0
        var failure: Throwable? = null
        var resultGate: CompletableDeferred<Unit>? = null

        override suspend fun inspect(
            ticket: String,
            input: AssociationInput,
        ): AssociationFileInspection {
            calls++
            resultGate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return result
        }
    }

    private class MemorySessions : AssociationSessionRepository {
        var current: AssociationSession? = null
        var creations = 0
        var writes = 0
        var readFailure: Throwable? = null

        override suspend fun create(input: AssociationInput): String {
            creations++
            current = AssociationSession(input)
            return "ticket"
        }

        override suspend fun read(ticket: String): AssociationSession {
            readFailure?.let { throw it }
            return checkNotNull(current)
        }

        override suspend fun write(ticket: String, value: AssociationSession): Boolean {
            if (value.revision <= checkNotNull(current).revision) return false
            writes++
            current = value
            return true
        }

        override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray): Unit =
            error("Unused")

        override suspend fun readBytes(ticket: String, name: String): ByteArray = error("Unused")

        override suspend fun release(ticket: String) {
            current = null
        }
    }
}
