package io.legado.app.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineCleanupTest {
    @Test
    fun cleanupFinishesAfterItsOwnerIsCancelled() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        val gate = CompletableDeferred<Unit>()
        var completed = false
        val cleanup = owner.launchCleanup {
            gate.await()
            completed = true
        }
        owner.cancel()
        gate.complete(Unit)
        cleanup.join()
        assertTrue(completed)
    }

    @Test
    fun cleanupAlsoStartsWhenTheOwnerHasAlreadyEnded() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        owner.cancel()
        var completed = false
        owner.launchCleanup { completed = true }.join()
        assertTrue(completed)
    }
}
