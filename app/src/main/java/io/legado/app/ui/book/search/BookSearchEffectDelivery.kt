package io.legado.app.ui.book.search

import io.legado.app.model.webBook.BookSearchReceipt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A prepared destination owns only its UUID; complete book identity stays in its private file. */
internal data class PreparedBookSearchEffect(
    val receipt: BookSearchReceipt,
    val bookInfoTicket: String? = null,
)

/** Preparation is cancellable; a resumed host consumes its receipt before synchronous delivery. */
internal class BookSearchEffectDelivery(
    private val available: () -> Boolean,
    private val prepare: suspend (BookSearchReceipt) -> PreparedBookSearchEffect,
    private val consume: (String) -> BookSearchReceipt?,
    private val handle: (PreparedBookSearchEffect) -> Unit,
    private val abandon: suspend (PreparedBookSearchEffect) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    suspend fun deliver(receipt: BookSearchReceipt) {
        if (!available()) return
        var prepared: PreparedBookSearchEffect? = null
        var transferred = false
        try {
            val destination = prepare(receipt)
            prepared = destination
            currentCoroutineContext().ensureActive()
            if (!available() || consume(receipt.id) == null) return
            // No suspension is allowed between consuming and handing ownership to the platform.
            handle(destination)
            transferred = true
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure(error)
        } finally {
            val owned = prepared
            if (owned != null && !transferred) {
                withContext(NonCancellable) {
                    try {
                        abandon(owned)
                    } catch (error: Exception) {
                        failure(error)
                    }
                }
            }
        }
    }
}
