package io.legado.app.ui.association

import io.legado.app.data.association.AssociationBookPreview
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationOperationResult
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileAssociationLoadingStateTest {
    private val input =
        AssociationInput(AssociationHostKind.File, AssociationInputKind.SharedText, text = "source")
    private val preview =
        AssociationBookPreview("book", "file:///private/book.txt", "book.txt", "private metadata")

    @Test
    fun completedInspectionHidesLoadingBeforeImportDialogDelivery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val fixture =
            AssociationStateFixture(
                AssociationSession(input),
                inspect = {
                    gate.await()
                    AssociationFileInspection(
                        importType = "bookSource",
                        source = "file:///private/source.json",
                    )
                },
            )
        try {
            runCurrent()
            assertTrue(fixture.model.state.value.busy)
            gate.complete(Unit)
            runCurrent()
            assertFalse(fixture.model.state.value.busy)
            assertEquals(
                AssociationNativeKind.ImportDialog,
                fixture.model.state.value.session!!.effects.single().kind,
            )
        } finally {
            gate.complete(Unit)
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun permissionDenialAndFolderPickerLeaveLoadingHidden() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val permission =
            AssociationStateFixture(
                AssociationSession(
                    input.copy(kind = AssociationInputKind.View, uris = listOf("file:///book.txt"))
                )
            )
        val directory =
            AssociationStateFixture(
                AssociationSession(
                    input,
                    phase = AssociationPhase.Preview,
                    previews = listOf(preview),
                    selectedIds = listOf("book"),
                )
            )
        try {
            runCurrent()
            assertFalse(permission.model.state.value.busy)
            val request = permission.model.state.value.session!!.effects.single()
            permission.model.claimNative(request)
            permission.model.permissionResult(request, false)
            runCurrent()
            assertFalse(permission.model.state.value.busy)
            assertEquals(
                "permissionDenied",
                permission.model.state.value.session!!.effects.single().type,
            )
            directory.model.requestDirectory()
            runCurrent()
            directory.model.chooseSystemDirectory()
            runCurrent()
            assertFalse(directory.model.state.value.busy)
            assertTrue(directory.model.state.value.session!!.choosingDirectory)
            assertEquals(
                AssociationNativeKind.SelectDirectory,
                directory.model.state.value.session!!.effects.single().kind,
            )
        } finally {
            permission.close()
            directory.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun acceptedCopyOwnsLoadingUntilItsResultIsDurable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val fixture =
            AssociationStateFixture(
                AssociationSession(
                    input,
                    phase = AssociationPhase.Preview,
                    previews = listOf(preview),
                    selectedIds = listOf("book"),
                ),
                mutate = {
                    assertEquals("local-import", it.kind)
                    gate.await()
                    AssociationOperationResult(importedCount = 1, selectedCount = 1)
                },
            )
        try {
            runCurrent()
            fixture.model.confirmOperation("local-import", "file:///private/books")
            runCurrent()
            assertTrue(fixture.model.state.value.busy)
            assertTrue(fixture.sessions.current.operation!!.accepted)
            gate.complete(Unit)
            runCurrent()
            assertFalse(fixture.model.state.value.busy)
            assertEquals(AssociationPhase.Finished, fixture.sessions.current.phase)
            assertEquals(1, fixture.acceptedMutations)
        } finally {
            gate.complete(Unit)
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
