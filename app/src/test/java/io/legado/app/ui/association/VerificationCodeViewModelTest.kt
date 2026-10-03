package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.VerificationSourceRepository
import io.legado.app.help.source.SourceVerificationHelp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerificationCodeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun draftAndDeleteConfirmationSurviveRecreation() {
        val handle = arguments()
        val model = VerificationCodeViewModel(handle, FakeOperations())
        model.updateCode("Ab12")
        model.requestDelete()
        val restored =
            VerificationCodeViewModel(
                SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
                FakeOperations(),
            )
        assertEquals("Ab12", restored.state.value.code)
        assertTrue(restored.state.value.deleteConfirmation)
        assertEquals("Example", restored.state.value.sourceName)
        restored.cancelDelete()
        assertFalse(restored.state.value.deleteConfirmation)
    }

    @Test
    fun submissionUsesOnlyItsOwnRequestAndIsNotRepeatedAfterRestore() {
        val submitted = mutableListOf<Pair<String?, String>>()
        val handle = arguments("request-a")
        val model =
            VerificationCodeViewModel(handle, FakeOperations()) { key, code ->
                submitted += key to code
            }
        model.updateCode("1234")
        model.submit()
        model.submit()
        val restored =
            VerificationCodeViewModel(handle, FakeOperations()) { key, code ->
                submitted += key to code
            }
        restored.submit()
        assertEquals(listOf("request-a" to "1234"), submitted)
        assertTrue(restored.state.value.closeRequested)
    }

    @Test
    fun submissionDoesNotResolveAnotherRegisteredRequest() {
        val own = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val other = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            val model = VerificationCodeViewModel(arguments(own), FakeOperations())
            model.updateCode("code-a")
            model.submit()
            assertEquals("" to "code-a", SourceVerificationHelp.getResult(own))
            assertNull(SourceVerificationHelp.getResult(other))
            // Dismissal's cancellation check must preserve an already submitted result.
            SourceVerificationHelp.checkResult(own)
            assertEquals("" to "code-a", SourceVerificationHelp.getResult(own))
        } finally {
            SourceVerificationHelp.clearResult(own)
            SourceVerificationHelp.clearResult(other)
        }
    }

    @Test
    fun clearingCancelledRequestCannotReceiveLateSubmission() {
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val model = VerificationCodeViewModel(arguments(key), FakeOperations())
        SourceVerificationHelp.clearResult(key)
        model.updateCode("late")
        model.submit()
        assertNull(SourceVerificationHelp.getResult(key))
    }

    @Test
    fun disableRunsOnceAndClosesOnlyAfterCompletion() =
        runTest(dispatcher) {
            val operations = FakeOperations().apply { gate = CompletableDeferred() }
            val model = VerificationCodeViewModel(arguments(), operations)
            model.disableSource()
            model.disableSource()
            model.submit()
            model.updateCode("ignored")
            runCurrent()
            assertEquals(listOf("origin" to SourceType.book), operations.disabled)
            assertTrue(model.state.value.busy)
            assertFalse(model.state.value.closeRequested)
            assertEquals("", model.state.value.code)
            operations.gate!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.state.value.closeRequested)
            assertFalse(model.state.value.busy)
        }

    @Test
    fun deleteRequiresConfirmationAndUsesSourceIdentity() =
        runTest(dispatcher) {
            val operations = FakeOperations()
            val model = VerificationCodeViewModel(arguments(), operations)
            model.deleteSource()
            advanceUntilIdle()
            assertTrue(operations.deleted.isEmpty())
            model.requestDelete()
            model.deleteSource()
            advanceUntilIdle()
            assertEquals(listOf("origin" to SourceType.book), operations.deleted)
            assertFalse(model.state.value.deleteConfirmation)
            assertTrue(model.state.value.closeRequested)
        }

    @Test
    fun sourceFailureKeepsDialogOpenAndCanBeRetried() =
        runTest(dispatcher) {
            val operations = FakeOperations().apply { error = IllegalStateException("Unavailable") }
            val model = VerificationCodeViewModel(arguments(), operations)
            model.disableSource()
            advanceUntilIdle()
            assertEquals("Unavailable", model.state.value.error)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.closeRequested)
            operations.error = null
            model.disableSource()
            advanceUntilIdle()
            assertNull(model.state.value.error)
            assertTrue(model.state.value.closeRequested)
        }

    @Test
    fun clearingViewModelCancelsPendingOperationWithoutRequestingClose() =
        runTest(dispatcher) {
            val operations = FakeOperations().apply { gate = CompletableDeferred() }
            val model = VerificationCodeViewModel(arguments(), operations)
            val store = ViewModelStore().apply { put("verification", model) }
            model.disableSource()
            runCurrent()
            store.clear()
            advanceUntilIdle()
            assertFalse(model.state.value.closeRequested)
            assertTrue(model.state.value.busy)
            assertNull(model.state.value.error)
        }

    @Test
    fun lateNonCancellableSourceCompletionCannotCloseDisposedModel() =
        runTest(dispatcher) {
            val operations =
                FakeOperations().apply {
                    gate = CompletableDeferred()
                    ignoreCancellation = true
                }
            val model = VerificationCodeViewModel(arguments(), operations)
            val store = ViewModelStore().apply { put("verification", model) }
            model.disableSource()
            runCurrent()
            store.clear()
            operations.gate!!.complete(Unit)
            advanceUntilIdle()
            assertFalse(model.state.value.closeRequested)
            assertTrue(model.state.value.busy)
            assertNull(model.state.value.error)
        }

    private fun arguments(key: String = "request") =
        SavedStateHandle(
            mapOf(
                "verificationResultKey" to key,
                "sourceOrigin" to "origin",
                "sourceName" to "Example",
                "sourceType" to SourceType.book,
                "imageUrl" to "https://example.com/captcha.png",
            )
        )

    private class FakeOperations : VerificationSourceRepository {
        val disabled = mutableListOf<Pair<String, Int>>()
        val deleted = mutableListOf<Pair<String, Int>>()
        var gate: CompletableDeferred<Unit>? = null
        var error: Exception? = null
        var ignoreCancellation = false

        override suspend fun disable(origin: String, type: Int) {
            disabled += origin to type
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            error?.let { throw it }
        }

        override suspend fun delete(origin: String, type: Int) {
            deleted += origin to type
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            error?.let { throw it }
        }
    }
}
