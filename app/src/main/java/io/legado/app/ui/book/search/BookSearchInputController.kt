package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchInputRepository
import io.legado.app.model.webBook.BookSearchDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transfers navigation inputs into one owner before releasing their separate private files. */
internal class BookSearchInputController(
    private val session: String,
    private val saved: SavedStateHandle,
    private val drafts: BookSearchDraftRepository,
    preferences: BookSearchPreferencesRepository,
    private val owner: CoroutineScope,
    private val cleanupOwner: CoroutineScope,
    private val state: () -> BookSearchUiState,
    private val update: ((BookSearchDraft) -> BookSearchDraft) -> Unit,
    private val checkpoint: suspend () -> Unit,
    private val queryChanged: (String) -> Unit,
    private val search: (String) -> Unit,
    private val failure: (Throwable) -> Unit,
    private val cleanupFailure: (Throwable) -> Unit,
) {
    private val inputs = BookSearchInputRepository(drafts, preferences)
    private var request: Job? = null
    private var stopped = false
    private var pendingSearchTicket: String? = null

    fun ready() {
        if (stopped || !state().ready || state().settingsBusy || request?.isActive == true) return
        val incoming = saved.get<String>("searchIncomingTicket")
        if (incoming != null) accept(incoming)
        else if (
            state().draft.navigationSeed &&
                (!state().draft.initialEntryAccepted || pendingSearchTicket == session)
        )
            acceptInitialSeed()
    }

    fun receive(ticket: String) {
        if (stopped || ticket == session) return
        cancelPending()
        saved["searchIncomingTicket"] = ticket
        ready()
    }

    fun receiveLegacy(query: String?, scope: String?, newIntent: Boolean) {
        if (stopped || (!newIntent && saved.get<Boolean>("searchLegacyAccepted") == true)) return
        cancelPending()
        request = owner.launch {
            var unreturned: String? = null
            try {
                val prepared = inputs.prepare(query, scope)
                unreturned = prepared
                currentCoroutineContext().ensureActive()
                if (stopped) return@launch
                saved["searchLegacyAccepted"] = true
                saved["searchIncomingTicket"] = prepared
                unreturned = null
                // Preparation has transferred ownership; acceptance is a separate cancellable job.
                request = null
                ready()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) failure(error)
            } finally {
                unreturned?.let { ticket -> withContext(NonCancellable) { inputs.abandon(ticket) } }
            }
        }
    }

    private fun acceptInitialSeed() {
        request = owner.launch {
            try {
                val query = state().draft.query
                pendingSearchTicket = session
                update {
                    it.copy(
                        initialEntryAccepted = true,
                        acceptedNavigationTicket = session,
                        submittedKey = query.trim(),
                        interrupted = query.isNotBlank(),
                    )
                }
                checkpoint()
                currentCoroutineContext().ensureActive()
                pendingSearchTicket = null
                if (!stopped && query.isNotBlank()) search(query.trim())
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) failure(error)
            }
        }
    }

    private fun accept(ticket: String) {
        if (request?.isActive == true) return
        request = owner.launch {
            try {
                val current = state().draft
                val alreadyAccepted = current.acceptedNavigationTicket == ticket
                if (!alreadyAccepted) {
                    pendingSearchTicket = ticket
                    val input = drafts.existing(ticket)
                    currentCoroutineContext().ensureActive()
                    if (stopped || saved.get<String>("searchIncomingTicket") != ticket)
                        return@launch
                    update { draft ->
                        draft.copy(
                            query = input.query,
                            selectionStart = input.selectionStart,
                            selectionEnd = input.selectionEnd,
                            scope = input.scope,
                            submittedKey = input.query.trim(),
                            results = emptyList(),
                            manualStop = false,
                            inputHelp = input.query.isBlank(),
                            interrupted = input.query.isNotBlank(),
                            hasMore = true,
                            searched = 0,
                            total = 0,
                            initialEntryAccepted = true,
                            acceptedNavigationTicket = ticket,
                            effects = emptyList(),
                            emptyScopeConfirmation = false,
                        )
                    }
                    queryChanged(input.query)
                }
                // Ownership is transferred only after this Activity's complete input is durable.
                checkpoint()
                currentCoroutineContext().ensureActive()
                if (stopped || saved.get<String>("searchIncomingTicket") != ticket) return@launch
                withContext(NonCancellable) { inputs.abandon(ticket) }
                currentCoroutineContext().ensureActive()
                saved.remove<String>("searchIncomingTicket")
                val query = state().draft.query.trim()
                // A restored accepted ticket is cleanup only, never a second automatic search.
                val shouldSearch = pendingSearchTicket == ticket
                pendingSearchTicket = null
                if (!stopped && shouldSearch && query.isNotEmpty()) search(query)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) failure(error)
            }
        }
    }

    /** New user input supersedes any navigation input still waiting to be accepted. */
    fun cancelPending() {
        request?.cancel()
        pendingSearchTicket = null
        saved.remove<String>("searchIncomingTicket")?.let { ticket ->
            if (ticket != session)
                cleanupOwner.launch {
                    try {
                        inputs.abandon(ticket)
                    } catch (error: Exception) {
                        cleanupFailure(error)
                    }
                }
        }
    }

    fun stop() {
        stopped = true
        cancelPending()
    }
}
