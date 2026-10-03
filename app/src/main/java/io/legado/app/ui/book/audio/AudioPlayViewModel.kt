package io.legado.app.ui.book.audio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayViewModel(application: Application) : AndroidViewModel(application) {
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

    private var requestGeneration = 0L
    private var initTask: Job? = null
    private var lyricTask: Job? = null
    private var coverTask: Job? = null
    private var cacheAction: AudioCacheAction? = null
    private var cacheSession: String? = null

    internal fun requestCacheFolder(action: AudioCacheAction? = null) {
        if (cacheSession != null) return
        cacheSession = java.util.UUID.randomUUID().toString()
        cacheAction = action
        update { copy(folderRequest = cacheSession) }
    }

    internal fun ensureCache(action: AudioCacheAction) {
        if (cacheSession != null) return
        val session = java.util.UUID.randomUUID().toString()
        cacheSession = session
        cacheAction = action
        viewModelScope.launch {
            val available = repository.cacheFolderAvailable(AppConfig.audioCacheTreeUri)
            if (cacheSession != session) return@launch
            if (available) update { copy(cacheReady = session) }
            else update { copy(folderRequest = session) }
        }
    }

    internal fun selectedCacheFolder(uri: String?) {
        val session = cacheSession ?: return
        if (uri == null) {
            cacheSession = null
            cacheAction = null
            return
        }
        viewModelScope.launch {
            if (repository.cacheFolderAvailable(uri)) {
                update { copy(selectedCacheFolder = uri, cacheReady = session) }
            } else {
                getApplication<Application>().toastOnUi(R.string.audio_cache_folder_invalid)
                cacheSession = null
                cacheAction = null
            }
        }
    }

    internal fun claimCacheAction(session: String): AudioCacheAction? {
        if (cacheSession != session) return null
        val action = cacheAction
        cacheAction = null
        cacheSession = null
        return action
    }

    internal fun clearCache(action: AudioCacheAction.Clear) {
        val treeUri = AppConfig.audioCacheTreeUri
        viewModelScope.launch(NonCancellable) {
            val removed = repository.clearCachedChapter(action, treeUri)
            getApplication<Application>()
                .toastOnUi(
                    if (removed) R.string.audio_cache_current_chapter_cleared
                    else R.string.audio_cache_current_chapter_not_found
                )
        }
    }

    internal fun addToShelf() {
        val book = AudioPlay.book ?: return
        val source = AudioPlay.bookSource
        update { copy(askShelf = false) }
        viewModelScope.launch(NonCancellable) {
            repository.addToShelf(book, source)
            if (AudioPlay.book?.bookUrl == book.bookUrl) update { copy(shelfAdded = true) }
        }
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

    internal fun initialize(bookUrl: String?, freshRequest: Boolean = false) {
        // A replacement host reuses its retained request, including any accepted source change.
        if (!freshRequest && (state.value.ready || initTask?.isActive == true)) {
            if (state.value.ready) snapshot()
            return
        }
        requestGeneration++
        initTask?.cancel()
        update { copy(ready = false, closeRequested = false) }
        initTask = viewModelScope.launch {
            try {
                when (repository.initialize(bookUrl)) {
                    true -> {
                        snapshot()
                        update { copy(ready = true) }
                        AudioPlay.saveRead(true)
                    }
                    false -> {
                        getApplication<Application>().toastOnUi(R.string.error_load_toc)
                        update { copy(closeRequested = true) }
                    }
                    null -> {
                        getApplication<Application>().toastOnUi(R.string.no_book)
                        update { copy(closeRequested = true) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.put("音频播放初始化失败", failure, true)
                update { copy(closeRequested = true) }
            }
        }
    }

    fun upSource() {
        viewModelScope.launch {
            repository.source()
            snapshot()
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>, onSuccess: () -> Unit) {
        val oldBook = AudioPlay.book
        val generation = requestGeneration
        // Once a source migration is accepted, deliver its business completion even if the host
        // rotates.
        viewModelScope.launch(NonCancellable) {
            repository.changeSource(oldBook, source, book, toc)
            if (generation == requestGeneration) snapshot()
            onSuccess()
        }
    }

    private val navigationSessions = mutableMapOf<String, Book>()

    internal fun claimNavigation(key: String): Book? = navigationSessions.remove(key)

    internal fun changeToText(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit) {
        val oldBook = AudioPlay.book
        val generation = requestGeneration
        viewModelScope.launch(NonCancellable) {
            repository.changeToText(oldBook, book, toc)
            onSuccess()
            if (generation != requestGeneration) return@launch
            val key = java.util.UUID.randomUUID().toString()
            navigationSessions[key] = book
            update { copy(bookNavigation = key) }
        }
    }

    fun removeFromBookshelf() {
        val book = AudioPlay.book ?: return
        viewModelScope.launch(NonCancellable) {
            repository.removeFromBookshelf(book)
            if (AudioPlay.book?.bookUrl == book.bookUrl) update { copy(closeRequested = true) }
        }
    }
}
