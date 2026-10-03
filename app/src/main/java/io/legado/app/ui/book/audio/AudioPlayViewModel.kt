package io.legado.app.ui.book.audio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayViewModel(application: Application, saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val restoringSession = saved.contains(SESSION_KEY)
    private val sessionTicket =
        saved.get<String>(SESSION_KEY)
            ?: java.util.UUID.randomUUID().toString().also { saved[SESSION_KEY] = it }
    private val session =
        AudioSessionController(
            sessionTicket,
            FileAudioPlaybackSessions(
                java.io.File(application.filesDir, "audio-playback-sessions")
            ),
            allowCreate = !restoringSession,
        )
    private val acceptedJobs = mutableSetOf<Job>()

    private val repository = AudioPlayRepository(application)
    private val mutableState =
        MutableStateFlow(
            AudioPlayUiState(
                supportsSpeed = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
            )
        )
    internal val state = mutableState.asStateFlow()

    internal fun update(change: AudioPlayUiState.() -> AudioPlayUiState) {
        mutableState.update { it.change() }
    }

    private var requestOwner: Long? = null
    private var requestGeneration = 0L
    private var initTask: Job? = null
    private var lyricTask: Job? = null
    private var coverTask: Job? = null
    private var cacheAction: AudioCacheAction? = null
    private var cacheSession: String? = null
    private var cachePreparing = false
    private var lastEntryBookUrl: String? = null

    private fun cacheCheckpoint(
        token: String,
        action: AudioCacheAction?,
        phase: AudioCachePhase,
    ): AudioCacheCheckpoint =
        when (action) {
            null -> AudioCacheCheckpoint(token, AudioCacheKind.Folder, phase = phase)
            is AudioCacheAction.Download ->
                AudioCacheCheckpoint(
                    token,
                    AudioCacheKind.Download,
                    action.bookUrl,
                    action.start,
                    action.endInclusive,
                    phase = phase,
                )
            is AudioCacheAction.Clear ->
                AudioCacheCheckpoint(
                    token,
                    AudioCacheKind.Clear,
                    action.bookUrl,
                    chapter = action.chapter,
                    phase = phase,
                )
        }

    internal fun requestCacheFolder(action: AudioCacheAction? = null) {
        if (cachePreparing || cacheSession != null) return
        prepareCache(action, folderRequired = true)
    }

    internal fun ensureCache(action: AudioCacheAction) {
        if (cachePreparing || cacheSession != null) return
        prepareCache(action, folderRequired = false)
    }

    private fun prepareCache(action: AudioCacheAction?, folderRequired: Boolean) {
        cachePreparing = true
        acceptedWrite {
            try {
                val token = java.util.UUID.randomUUID().toString()
                val available =
                    !folderRequired && repository.cacheFolderAvailable(AppConfig.audioCacheTreeUri)
                val phase = if (available) AudioCachePhase.Ready else AudioCachePhase.FolderPending
                session.update { it.copy(cache = cacheCheckpoint(token, action, phase)) }
                cacheAction = action
                cacheSession = token
                update {
                    copy(
                        folderRequest = if (available) null else token,
                        cacheReady = if (available) token else null,
                        recovery = null,
                    )
                }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Cache)
            } finally {
                cachePreparing = false
            }
        }
    }

    internal fun selectedCacheFolder(uri: String?) {
        acceptedWrite {
            try {
                val cached = session.load().cache ?: return@acceptedWrite
                // A live picker result is allowed to complete its already-claimed chooser after
                // recreation.
                if (cached.phase != AudioCachePhase.FolderClaimed) return@acceptedWrite
                if (uri == null) {
                    session.update { it.copy(cache = null) }
                    cacheSession = null
                    cacheAction = null
                    update { copy(recovery = null) }
                } else if (repository.cacheFolderAvailable(uri)) {
                    session.update {
                        it.copy(cache = cached.copy(folder = uri, phase = AudioCachePhase.Ready))
                    }
                    cacheSession = cached.token
                    cacheAction = cached.action()
                    update {
                        copy(selectedCacheFolder = uri, cacheReady = cached.token, recovery = null)
                    }
                } else
                    pendingFailure(
                        IllegalArgumentException(
                            getApplication<Application>()
                                .getString(R.string.audio_cache_folder_invalid)
                        ),
                        AudioRecovery.Cache,
                    )
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Cache)
            }
        }
    }

    internal suspend fun claimFolder(token: String): Boolean =
        claimReceipt(AudioRecovery.Cache) {
            var claimed = false
            session.update {
                val cached = it.cache
                if (cached?.token != token || cached.phase != AudioCachePhase.FolderPending) it
                else {
                    claimed = true
                    it.copy(cache = cached.copy(phase = AudioCachePhase.FolderClaimed))
                }
            }
            if (claimed) update { copy(folderRequest = null) }
            claimed
        }

    internal suspend fun claimCache(token: String): Boolean =
        claimReceipt(AudioRecovery.Cache) {
            var claimed = false
            session.update {
                val cached = it.cache
                if (cached?.token != token || cached.phase != AudioCachePhase.Ready) it
                else {
                    claimed = true
                    it.copy(cache = cached.copy(phase = AudioCachePhase.Claimed))
                }
            }
            if (claimed) update { copy(cacheReady = null, selectedCacheFolder = null) }
            claimed
        }

    internal fun claimCacheAction(token: String): AudioCacheAction? {
        if (cacheSession != token) return null
        val action = cacheAction
        cacheAction = null
        cacheSession = null
        return action
    }

    internal fun completeCacheNative(token: String) {
        acceptedWrite {
            try {
                session.update {
                    if (it.cache?.token == token)
                        it.copy(cache = it.cache.copy(phase = AudioCachePhase.Complete))
                    else it
                }
                update { copy(recovery = null) }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Cache)
            }
        }
    }

    internal fun clearCache(action: AudioCacheAction.Clear, token: String) {
        val treeUri = AppConfig.audioCacheTreeUri
        acceptedWrite {
            try {
                var accepted = false
                session.update {
                    if (it.cache?.token == token) {
                        accepted = true
                        it.copy(cache = it.cache.copy(phase = AudioCachePhase.Accepted))
                    } else it
                }
                if (!accepted) return@acceptedWrite
                val removed = repository.clearCachedChapter(action, treeUri)
                session.update {
                    if (it.cache?.token == token)
                        it.copy(cache = it.cache.copy(phase = AudioCachePhase.Complete))
                    else it
                }
                getApplication<Application>()
                    .toastOnUi(
                        if (removed) R.string.audio_cache_current_chapter_cleared
                        else R.string.audio_cache_current_chapter_not_found
                    )
                update { copy(recovery = null) }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Cache)
            }
        }
    }

    internal fun addToShelf() {
        val book = AudioPlay.book ?: return
        val source = AudioPlay.bookSource
        update { copy(askShelf = false) }
        acceptedWrite {
            try {
                repository.addToShelf(book, source)
                if (AudioPlay.book?.bookUrl == book.bookUrl) {
                    session.update {
                        it.copy(
                            shelfToken = java.util.UUID.randomUUID().toString(),
                            shelfClaimed = false,
                        )
                    }
                    update { copy(shelfAdded = true) }
                }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Shelf)
            }
        }
    }

    internal fun completeShelf() {
        acceptedWrite {
            try {
                session.update { it.copy(shelfToken = null, shelfClaimed = false) }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Shelf)
            }
        }
    }

    private fun pendingFailure(error: Exception, recovery: AudioRecovery) {
        update {
            copy(
                recovery = recovery,
                recoveryError = error.localizedMessage,
                folderRequest = null,
                cacheReady = null,
                bookNavigation = null,
                closeRequested = false,
                shelfAdded = false,
            )
        }
    }

    private suspend fun claimReceipt(
        recovery: AudioRecovery,
        claim: suspend () -> Boolean,
    ): Boolean {
        return try {
            claim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pendingFailure(error, recovery)
            false
        }
    }

    internal fun retryRecovery() {
        val recovery = state.value.recovery ?: return
        acceptedWrite {
            try {
                val saved = session.load()
                when (recovery) {
                    AudioRecovery.Entry -> {
                        update { copy(recovery = null, recoveryError = null) }
                        initialize(lastEntryBookUrl, freshRequest = true)
                    }
                    AudioRecovery.Cache -> {
                        val cached = saved.cache ?: return@acceptedWrite
                        // Retrying is a new explicit command with a new nonce, never a replay of
                        // the old claim.
                        val token = java.util.UUID.randomUUID().toString()
                        val phase =
                            if (
                                cached.folder != null ||
                                    repository.cacheFolderAvailable(AppConfig.audioCacheTreeUri)
                            )
                                AudioCachePhase.Ready
                            else AudioCachePhase.FolderPending
                        session.update {
                            it.copy(cache = cached.copy(token = token, phase = phase))
                        }
                        cacheSession = token
                        cacheAction = cached.action()
                        update {
                            copy(
                                recovery = null,
                                recoveryError = null,
                                folderRequest =
                                    if (phase == AudioCachePhase.FolderPending) token else null,
                                cacheReady = if (phase == AudioCachePhase.Ready) token else null,
                                selectedCacheFolder = cached.folder,
                            )
                        }
                    }
                    AudioRecovery.Navigation -> {
                        val navigation = saved.navigation ?: return@acceptedWrite
                        val token = java.util.UUID.randomUUID().toString()
                        session.update {
                            it.copy(navigation = navigation.copy(token = token, claimed = false))
                        }
                        navigationSessions[token] = navigation.book
                        update {
                            copy(recovery = null, recoveryError = null, bookNavigation = token)
                        }
                    }
                    AudioRecovery.Close -> {
                        session.update {
                            it.copy(
                                closeToken = java.util.UUID.randomUUID().toString(),
                                closeClaimed = false,
                            )
                        }
                        update {
                            copy(recovery = null, recoveryError = null, closeRequested = true)
                        }
                    }
                    AudioRecovery.Shelf -> {
                        session.update {
                            it.copy(
                                shelfToken = java.util.UUID.randomUUID().toString(),
                                shelfClaimed = false,
                            )
                        }
                        update { copy(recovery = null, recoveryError = null, shelfAdded = true) }
                    }
                }
            } catch (error: Exception) {
                pendingFailure(error, recovery)
            }
        }
    }

    internal fun dismissRecovery() {
        val recovery = state.value.recovery ?: return
        acceptedWrite {
            try {
                session.update {
                    when (recovery) {
                        AudioRecovery.Cache -> it.copy(cache = null)
                        AudioRecovery.Navigation -> it.copy(navigation = null)
                        AudioRecovery.Close -> it.copy(closeToken = null)
                        AudioRecovery.Shelf -> it.copy(shelfToken = null)
                        AudioRecovery.Entry -> it
                    }
                }
                if (recovery == AudioRecovery.Cache) {
                    cacheSession = null
                    cacheAction = null
                }
                if (recovery == AudioRecovery.Navigation) navigationSessions.clear()
                update { copy(recovery = null, recoveryError = null) }
            } catch (error: Exception) {
                pendingFailure(error, recovery)
            }
        }
    }

    internal suspend fun claimClose(): Boolean =
        claimReceipt(AudioRecovery.Close) {
            var claimed = false
            session.update {
                if (it.closeToken == null || it.closeClaimed) it
                else {
                    claimed = true
                    it.copy(closeClaimed = true)
                }
            }
            if (claimed) update { copy(closeRequested = false) }
            claimed
        }

    internal suspend fun claimShelf(): Boolean =
        claimReceipt(AudioRecovery.Shelf) {
            var claimed = false
            session.update {
                if (it.shelfToken == null || it.shelfClaimed) it
                else {
                    claimed = true
                    it.copy(shelfClaimed = true)
                }
            }
            if (claimed) update { copy(shelfAdded = false) }
            claimed
        }

    internal fun lyrics(text: String?) {
        lyricTask?.cancel()
        lyricTask = viewModelScope.launch {
            val lines = withContext(Dispatchers.Default) { parseAudioLyrics(text) }
            update { copy(lyrics = lines) }
        }
    }

    internal fun snapshot() {
        val book = AudioPlay.book
        update {
            copy(
                title = book?.name.orEmpty(),
                cover = book?.getDisplayCover(),
                coverOrigin = book?.getCoverSourceOrigin(),
                customButton = AudioPlay.bookSource?.customButton == true,
                hasLogin = AudioPlay.bookSource?.hasLogin() == true,
                wakeLock = AppConfig.audioPlayUseWakeLock,
                chapterIndex = AudioPlay.durChapterIndex,
                chapterCount = AudioPlay.simulatedChapterSize,
                playMode = AudioPlay.playMode.iconRes,
                speed = AudioPlayService.playSpeed,
            )
        }
        coverTask?.cancel()
        coverTask = viewModelScope.launch {
            val path = state.value.cover
            val origin = state.value.coverOrigin
            val cover = runCatching { repository.cover(path, origin) }.getOrNull()
            ensureActive()
            val backdrop = runCatching { repository.cover(path, origin, blur = true) }.getOrNull()
            ensureActive()
            update { copy(coverImage = cover, backdropImage = backdrop) }
        }
        lyrics(
            AudioPlay.durChapter?.getVariable("lyric")?.takeIf(String::isNotBlank)
                ?: AudioPlay.durLyric
        )
    }

    private fun acceptedWrite(block: suspend () -> Unit): Job {
        val job = viewModelScope.launch { withContext(NonCancellable) { block() } }
        acceptedJobs += job
        job.invokeOnCompletion { acceptedJobs -= job }
        return job
    }

    internal fun initialize(bookUrl: String?, freshRequest: Boolean = false) {
        // A replacement host reuses its retained request, including any accepted source change.
        if (!freshRequest && (state.value.ready || initTask?.isActive == true)) {
            if (state.value.ready) snapshot()
            return
        }
        lastEntryBookUrl = bookUrl ?: lastEntryBookUrl
        requestGeneration++
        navigationSessions.clear()
        initTask?.cancel()
        update {
            copy(
                ready = false,
                closeRequested = false,
                bookNavigation = null,
                askShelf = false,
                shelfAdded = false,
            )
        }
        val owner = repository.beginRequest()
        requestOwner = owner
        initTask = viewModelScope.launch {
            try {
                val stored = session.load()
                // A process-restored host still carries its original Intent. Accepted source
                // changes belong to the durable session and must win over that stale URL.
                val target =
                    if (restoringSession && !freshRequest) stored.bookUrl
                    else
                        bookUrl?.takeIf(String::isNotBlank)
                            ?: stored.bookUrl
                            ?: AudioPlay.book?.bookUrl
                lastEntryBookUrl = target
                session.update {
                    it.copy(
                        bookUrl = target,
                        navigation = if (freshRequest) null else it.navigation,
                        closeToken = if (freshRequest) null else it.closeToken,
                        shelfToken = if (freshRequest) null else it.shelfToken,
                    )
                }
                if (!freshRequest) restorePending(stored)
                if (!freshRequest && stored.navigation?.complete == false) {
                    update { copy(title = stored.navigation.book.name, ready = false) }
                    return@launch
                }
                val initialized = repository.initialize(target, owner)
                if (!repository.ownsRequest(owner)) return@launch
                when (initialized) {
                    true -> {
                        session.update {
                            if (repository.ownsRequest(owner))
                                it.copy(bookUrl = AudioPlay.book?.bookUrl)
                            else it
                        }
                        if (!repository.ownsRequest(owner)) return@launch
                        snapshot()
                        update { copy(ready = true) }
                        AudioPlay.saveRead(true)
                    }
                    false -> {
                        if (state.value.recovery != null) return@launch
                        getApplication<Application>().toastOnUi(R.string.error_load_toc)
                        session.update {
                            it.copy(
                                closeToken = java.util.UUID.randomUUID().toString(),
                                closeClaimed = false,
                            )
                        }
                        update { copy(closeRequested = true) }
                    }
                    null -> {
                        if (state.value.recovery != null) return@launch
                        getApplication<Application>().toastOnUi(R.string.no_book)
                        session.update {
                            it.copy(
                                closeToken = java.util.UUID.randomUUID().toString(),
                                closeClaimed = false,
                            )
                        }
                        update { copy(closeRequested = true) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.put("音频播放初始化失败", failure, true)
                pendingFailure(failure, AudioRecovery.Entry)
            }
        }
    }

    private fun restorePending(stored: AudioPlaybackCheckpoint) {
        stored.cache
            ?.takeIf { it.phase != AudioCachePhase.Complete }
            ?.let {
                cacheSession = it.token
                cacheAction = it.action()
            }
        stored.navigation?.let { navigationSessions[it.token] = it.book }
        val recovery = stored.recoveryProjection().recovery
        // Disk restore projects a manual recovery prompt, never a native effect.
        if (state.value.cacheReady == null) update { copy(recovery = recovery) }
    }

    fun upSource() {
        val owner = requestOwner ?: return
        viewModelScope.launch {
            repository.source(owner)
            if (!repository.ownsRequest(owner)) return@launch
            snapshot()
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>, onSuccess: () -> Unit) {
        val oldBook = AudioPlay.book
        val generation = requestGeneration
        val owner = requestOwner ?: return
        // Once a source migration is accepted, deliver its business completion even if the host
        // rotates.
        acceptedWrite {
            repository.changeSource(oldBook, source, book, toc, owner)
            if (generation == requestGeneration) {
                session.update { it.copy(bookUrl = AudioPlay.book?.bookUrl) }
                snapshot()
            }
            onSuccess()
        }
    }

    private val navigationSessions = mutableMapOf<String, Book>()

    internal fun completeNavigation(token: String) {
        acceptedWrite {
            try {
                session.update {
                    if (it.navigation?.token == token)
                        it.copy(navigation = it.navigation.copy(complete = true))
                    else it
                }
                update { copy(recovery = null) }
            } catch (error: Exception) {
                pendingFailure(error, AudioRecovery.Navigation)
            }
        }
    }

    internal fun interruptedReceipt(recovery: AudioRecovery, error: String? = null) {
        update {
            copy(
                recovery = recovery,
                recoveryError = error,
                folderRequest = null,
                cacheReady = null,
                bookNavigation = null,
                closeRequested = false,
                shelfAdded = false,
            )
        }
    }

    internal fun claimNavigation(key: String): Book? = navigationSessions.remove(key)

    internal suspend fun claimNavigationReceipt(key: String): Boolean =
        claimReceipt(AudioRecovery.Navigation) {
            var claimed = false
            session.update {
                val pending = it.navigation
                if (pending?.token != key || pending.claimed) it
                else {
                    claimed = true
                    it.copy(navigation = pending.copy(claimed = true))
                }
            }
            if (claimed) update { copy(bookNavigation = null) }
            claimed
        }

    internal fun changeToText(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit) {
        val oldBook = AudioPlay.book
        val generation = requestGeneration
        acceptedWrite {
            repository.changeToText(oldBook, book, toc)
            onSuccess()
            if (generation != requestGeneration) return@acceptedWrite
            val key = java.util.UUID.randomUUID().toString()
            val snapshot = book.copy(readConfig = book.readConfig?.copy())
            session.update { it.copy(navigation = AudioNavigationCheckpoint(key, snapshot)) }
            if (generation != requestGeneration) return@acceptedWrite
            navigationSessions[key] = snapshot
            update { copy(bookNavigation = key) }
        }
    }

    fun removeFromBookshelf() {
        val book = AudioPlay.book ?: return
        acceptedWrite {
            repository.removeFromBookshelf(book)
            if (AudioPlay.book?.bookUrl == book.bookUrl) {
                session.update {
                    it.copy(
                        closeToken = java.util.UUID.randomUUID().toString(),
                        closeClaimed = false,
                    )
                }
                update { copy(closeRequested = true) }
            }
        }
    }

    override fun onCleared() {
        requestOwner?.let(repository::retireRequest)
        val pending = acceptedJobs.toList() + listOfNotNull(initTask)
        cleanupScope.launch {
            pending.forEach { it.join() }
            try {
                session.close()
            } catch (error: Exception) {
                AppLog.put("音频会话清理失败", error)
            }
        }
        super.onCleared()
    }

    companion object {
        internal const val SESSION_KEY = "audioPlayback.session"
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
