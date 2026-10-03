package io.legado.app.data.repository

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns immutable receipts; the UI saves only [sessionId], never these complete payloads. */
class MangaReaderSessionController(
    val sessionId: String,
    private val repository: MangaReaderSessionRepository,
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<MangaReaderSession?>(null)
    val state: StateFlow<MangaReaderSession?> = mutableState.asStateFlow()
    private var released = false

    init {
        require(UUID.fromString(sessionId).toString() == sessionId)
    }

    suspend fun restore(launch: MangaReaderLaunch): MangaReaderSession = gate.withLock {
        check(!released)
        mutableState.value?.let {
            return@withLock it
        }
        val value = repository.read(sessionId) ?: MangaReaderSession(revision = 0, launch = launch)
        // Publish an accepted checkpoint even if its initiating owner is cancelled during IO.
        withContext(NonCancellable) {
            persist(value)
            mutableState.value = value
        }
        value
    }

    suspend fun checkpoint(
        menuVisible: Boolean,
        chapterIndex: Int,
        pageIndex: Int,
    ) {
        update { current ->
            current.copy(
                menuVisible = menuVisible,
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
            )
        }
    }

    suspend fun enqueue(request: MangaNativeRequest) {
        require(request.phase == MangaNativePhase.Pending)
        require(UUID.fromString(request.ticket).toString() == request.ticket)
        update { current ->
            if (current.nativeRequests.any { it.ticket == request.ticket }) current
            else current.copy(nativeRequests = current.nativeRequests + request)
        }
    }

    /**
     * Claim only from a resumed owner. Dispatch follows durable acceptance without a cancellation
     * boundary, so a cancelled claimant cannot strand an accepted receipt before platform launch.
     */
    suspend fun claimAndDispatch(
        ticket: String,
        resumed: () -> Boolean,
        dispatch: (MangaNativeRequest) -> Unit,
    ): Boolean {
        if (!resumed()) return false
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            gate.withLock {
                if (released) return@withLock false
                val current = mutableState.value ?: return@withLock false
                if (current.nativeRequests.any { it.phase == MangaNativePhase.Claimed }) {
                    return@withLock false
                }
                val request =
                    current.nativeRequests.firstOrNull {
                        it.ticket == ticket && it.phase == MangaNativePhase.Pending
                    } ?: return@withLock false
                val claimed = request.copy(phase = MangaNativePhase.Claimed)
                val accepted =
                    current.copy(
                        revision = current.revision + 1,
                        nativeRequests =
                            current.nativeRequests.map {
                                if (it.ticket == ticket) claimed else it
                            },
                    )
                persist(accepted)
                mutableState.value = accepted
                if (!resumed()) {
                    val deferred =
                        accepted.copy(
                            revision = accepted.revision + 1,
                            nativeRequests =
                                accepted.nativeRequests.map {
                                    if (it.ticket == ticket) request else it
                                },
                        )
                    persist(deferred)
                    mutableState.value = deferred
                    return@withLock false
                }
                try {
                    dispatch(claimed)
                } catch (error: Throwable) {
                    val failed =
                        accepted.copy(
                            revision = accepted.revision + 1,
                            nativeRequests =
                                accepted.nativeRequests.map {
                                    if (it.ticket == ticket)
                                        it.copy(phase = MangaNativePhase.Cancelled)
                                    else it
                                },
                        )
                    persist(failed)
                    mutableState.value = failed
                    throw error
                }
                true
            }
        }
    }

    suspend fun complete(ticket: String, cancelled: Boolean = false) {
        update { current ->
            current.copy(
                nativeRequests =
                    current.nativeRequests.map { request ->
                        if (request.ticket == ticket && request.phase == MangaNativePhase.Claimed) {
                            request.copy(
                                phase =
                                    if (cancelled) MangaNativePhase.Cancelled
                                    else MangaNativePhase.Complete
                            )
                        } else request
                    }
            )
        }
    }

    private suspend fun update(transform: (MangaReaderSession) -> MangaReaderSession) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            gate.withLock {
                if (released) return@withLock
                val current = checkNotNull(mutableState.value)
                val transformed = transform(current)
                if (transformed == current) return@withLock
                val accepted = transformed.copy(revision = current.revision + 1)
                persist(accepted)
                mutableState.value = accepted
            }
        }
    }

    private suspend fun persist(value: MangaReaderSession) {
        repository.write(sessionId, value)
        // Another restored owner or a tombstone may reject a stale write; it is not a receipt.
        check(repository.read(sessionId) == value) { "Manga session checkpoint was not accepted" }
    }

    suspend fun release() =
        withContext(NonCancellable) {
            gate.withLock {
                if (!released) {
                    repository.release(sessionId)
                    released = true
                    mutableState.value = null
                }
            }
        }
}
