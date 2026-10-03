package io.legado.app.ui.book.explore

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Preparation may suspend in IO. The claim is persisted only for a resumed owner, and a claim
 * interrupted before the synchronous handoff is durably rolled back for the next resumed owner.
 */
internal suspend fun deliverExploreBookDetail(
    prepare: suspend () -> String?,
    claim: suspend () -> Boolean,
    defer: suspend () -> Unit,
    handled: suspend () -> Unit,
    ready: () -> Boolean,
    native: (String) -> Unit,
) {
    var claimStarted = false
    var claimReturned = false
    var claimed = false
    var handedOff = false
    try {
        val ticket = prepare() ?: return
        currentCoroutineContext().ensureActive()
        if (!ready()) return
        claimStarted = true
        claimed = claim()
        claimReturned = true
        if (!claimed) return
        currentCoroutineContext().ensureActive()
        if (!ready()) return
        native(ticket)
        handedOff = true
        withContext(NonCancellable) { handled() }
    } finally {
        // A disk write can succeed and its cancelled return can throw before `claim()` returns.
        // Roll back that uncertain attempt as well; the nonce guard rejects a replaced request.
        if (!handedOff && (claimed || claimStarted && !claimReturned))
            withContext(NonCancellable) { defer() }
    }
}
