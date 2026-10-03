package io.legado.app.ui.book.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioRequestGateTest {
    @Test
    fun oldNetworkCompletionKeepsItsDatabaseResultButCannotReplaceNewBook() = runBlocking {
        val gate = AudioRequestGate()
        val oldNetwork = CompletableDeferred<Unit>()
        val database = mutableListOf<String>()
        var engineBook = "old-book"
        val oldOwner = gate.claim()
        val oldRequest =
            launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    oldNetwork.await()
                    database += "old-book"
                    gate.publish(oldOwner, "old-book", { engineBook }) { engineBook = "old-result" }
                }
            }
        oldRequest.cancel()
        val newOwner = gate.claim()
        val newRequest =
            launch(start = CoroutineStart.UNDISPATCHED) {
                database += "new-book"
                gate.publish(newOwner) { engineBook = "new-book" }
            }
        newRequest.join()
        oldNetwork.complete(Unit)
        oldRequest.join()
        assertEquals(listOf("new-book", "old-book"), database)
        assertEquals("new-book", engineBook)
    }

    @Test
    fun currentGenerationCannotPublishAgainstAnExternallyReplacedBook() {
        val gate = AudioRequestGate()
        val owner = gate.claim()
        var chapterCount = 12
        assertFalse(
            gate.publish(owner, "original-book", { "replacement-book" }) { chapterCount = 99 }
        )
        assertEquals(12, chapterCount)
    }

    @Test
    fun retiredOwnerDoesNotInvalidateItsReplacement() {
        val gate = AudioRequestGate()
        val retired = gate.claim()
        val current = gate.claim()
        gate.retire(retired)
        var published = false
        gate.publish(current) { published = true }
        assertEquals(true, published)
    }
}
