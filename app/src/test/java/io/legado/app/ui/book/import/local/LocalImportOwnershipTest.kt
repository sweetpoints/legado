package io.legado.app.ui.book.import.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalImportOwnershipTest {
    @Test
    fun canceledOldDirectoryResultCannotReplaceNewListingOrItsAcceptedReceipt() = runBlocking {
        val ownership = LocalImportOwnership()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = ownership.begin()
        var rows = listOf("first-folder")
        var acceptedDatabaseWrite = false
        val old = launch {
            withContext(NonCancellable) {
                entered.complete(Unit)
                finish.await()
                acceptedDatabaseWrite = true
                assertFalse(ownership.publish(first) { rows = listOf("late-old-result") })
            }
        }
        entered.await()
        old.cancel()
        val replacement = ownership.begin()
        ownership.publish(replacement) { rows = listOf("different-new-folder") }
        finish.complete(Unit)
        old.join()
        assertEquals(listOf("different-new-folder"), rows)
        assertEquals(true, acceptedDatabaseWrite)
    }
}
