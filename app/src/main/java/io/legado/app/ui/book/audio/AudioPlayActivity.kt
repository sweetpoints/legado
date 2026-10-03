package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.SourceCallBack
import io.legado.app.model.download.ChapterDownloadMode
import io.legado.app.service.AudioCacheService
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.download.ChapterDownloadDialog
import io.legado.app.ui.book.download.showChapterDownloadDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 音频播放 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    BaseComposeActivity(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack,
    SleepTimerDialog.CallBack,
    ChapterDownloadDialog.AudioHost {

    private val viewModel by
        viewModels<AudioPlayViewModel> {
            viewModelFactory {
                initializer {
                    val saved =
                        createSavedStateHandle().apply {
                            keys()
                                .filterNot { it == AudioPlayViewModel.SESSION_KEY }
                                .forEach { remove<Any?>(it) }
                        }
                    AudioPlayViewModel(application, saved)
                }
            }
        }

    private val tocActivityResult =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                if (it[0] != AudioPlay.book?.durChapterIndex || it[1] == 0) {
                    AudioPlay.skipTo(it[0] as Int)
                }
            }
        }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val audioCacheDirSelect =
        registerForActivityResult(HandleFileContract()) { result ->
            viewModel.selectedCacheFolder(result.uri?.toString())
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
        AudioPlay.register(this)
        viewModel.initialize(intent.getStringExtra("bookUrl"))
        initView()
    }

    private fun finishAfterInitError() {
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedBookUrl = intent.getStringExtra("bookUrl")
        if (shouldReuseCurrentAudioPlay(requestedBookUrl, AudioPlay.book?.bookUrl)) return
        viewModel.initialize(requestedBookUrl, freshRequest = true)
    }

    private fun menuAction(id: Int) {
        when (id) {
            R.id.menu_custom_btn -> {
                AudioPlay.bookSource?.let { source ->
                    AudioPlay.book?.let { book ->
                        SourceCallBack.callBackBtn(
                            this,
                            SourceCallBack.CLICK_CUSTOM_BUTTON,
                            source,
                            book,
                            AudioPlay.durChapter,
                            BookType.audio,
                        )
                    }
                }
            }
            R.id.menu_change_source ->
                AudioPlay.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }

            R.id.menu_login ->
                AudioPlay.bookSource?.let {
                    startActivity<SourceLoginActivity> {
                        putExtra("bookType", BookType.audio)
                    }
                }

            R.id.menu_wake_lock -> {
                AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
                viewModel.snapshot()
            }
            R.id.menu_copy_audio_url -> {
                AudioPlay.book?.let {
                    val url =
                        AudioPlay.durPlayUrl.ifBlank {
                            AudioPlay.durChapter?.resourceUrl.orEmpty()
                        }
                    SourceCallBack.callBackBtn(
                        this,
                        SourceCallBack.CLICK_COPY_PLAY_URL,
                        AudioPlay.bookSource,
                        it,
                        AudioPlay.durChapter,
                        BookType.audio,
                        url,
                    ) {
                        sendToClip(url)
                    }
                }
            }
            R.id.menu_audio_cache_folder -> selectAudioCacheFolder()
            R.id.menu_audio_cache_range -> showAudioCacheRange()
            R.id.menu_clear_current_audio_cache -> clearCurrentAudioCache()
            R.id.menu_edit_source ->
                AudioPlay.bookSource?.let {
                    sourceEditResult.launch {
                        putExtra("sourceUrl", it.bookSourceUrl)
                    }
                }

            /* 跳过片头片尾设定按钮 */
            R.id.menu_skip_credits ->
                AudioPlay.book?.let { book ->
                    lifecycleScope.launch {
                        try {
                            val dialog = AudioSkipCredits.prepare(this@AudioPlayActivity, book)
                            lifecycle.currentStateFlow.first {
                                it.isAtLeast(Lifecycle.State.RESUMED)
                            }
                            ensureActive()
                            if (!dialog.claimLaunch(this@AudioPlayActivity)) return@launch
                            ensureActive()
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                showDialogFragment(dialog)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            toastOnUi(error.localizedMessage.orEmpty())
                        }
                    }
                }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    private fun selectAudioCacheFolder() {
        viewModel.requestCacheFolder()
    }

    private fun launchCacheFolder() {
        audioCacheDirSelect.launch {
            title = getString(R.string.audio_cache_select_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun acceptCacheAction(session: String, selectedFolder: String?) {
        val action = viewModel.claimCacheAction(session)
        if (selectedFolder != null) {
            if (AppConfig.audioCacheTreeUri != selectedFolder) AudioCacheService.stop(this)
            AppConfig.audioCacheTreeUri = selectedFolder
            toastOnUi(R.string.audio_cache_folder_selected)
        }
        when (action) {
            is AudioCacheAction.Download -> {
                AudioCacheService.start(this, action.bookUrl, action.start, action.endInclusive)
                toastOnUi(R.string.audio_cache_start_range)
                viewModel.completeCacheNative(session)
            }
            is AudioCacheAction.Clear -> viewModel.clearCache(action, session)
            null -> viewModel.completeCacheNative(session)
        }
    }

    private fun showAudioCacheRange() {
        val book = AudioPlay.book ?: return
        val count =
            AudioPlay.simulatedChapterSize.takeIf { it > 0 } ?: book.simulatedTotalChapterNum()
        if (count > 0)
            showChapterDownloadDialog(
                book,
                ChapterDownloadMode.Audio,
                AudioPlay.durChapterIndex + 1,
                count,
            )
    }

    override fun downloadAudioRange(bookUrl: String, start: Int, endInclusive: Int) {
        viewModel.ensureCache(AudioCacheAction.Download(bookUrl, start, endInclusive))
    }

    private fun clearCurrentAudioCache() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter ?: return
        viewModel.ensureCache(AudioCacheAction.Clear(book.bookUrl, chapter.copy()))
    }

    private fun initView() {
        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) { mode ->
            viewModel.update { copy(playMode = mode.iconRes) }
        }
    }

    override fun upLyric(lyric: String?) {
        viewModel.lyrics(lyric)
    }

    override fun upLyricP(position: Int) {
        viewModel.update { copy(progress = position) }
    }

    @androidx.compose.runtime.Composable
    override fun Content(savedInstanceState: Bundle?) {
        AudioPlayRoute(
            model = viewModel,
            close = ::finishAfterInitError,
            folder = ::launchCacheFolder,
            cache = ::acceptCacheAction,
            navigate = ::navigateToBook,
            back = ::finish,
            menu = ::menuAction,
            shelfResult = {
                setResult(RESULT_OK)
                viewModel.completeShelf()
            },
            addShelf = viewModel::addToShelf,
            discardShelf = ::discardBook,
            action = ::playbackAction,
            seek = AudioPlay::adjustProgress,
            speed = AudioPlay::setSpeed,
            lyricSeek = ::seekToLyric,
        )
    }

    private fun navigateToBook(key: String) {
        viewModel.claimNavigation(key)?.let { book ->
            startActivityForBook(book)
            viewModel.completeNavigation(key)
            finish()
        }
    }

    private fun discardBook() {
        viewModel.update { copy(askShelf = false) }
        callBackBookEnd()
        viewModel.removeFromBookshelf()
    }

    private fun playbackAction(action: AudioPlayAction) {
        when (action) {
            AudioPlayAction.Play -> playButton()
            AudioPlayAction.Stop -> AudioPlay.stop()
            AudioPlayAction.Next -> AudioPlay.next()
            AudioPlayAction.Previous -> AudioPlay.prev()
            AudioPlayAction.Mode -> AudioPlay.changePlayMode()
            AudioPlayAction.Chapters -> AudioPlay.book?.let { tocActivityResult.launch(it.bookUrl) }
            AudioPlayAction.Timer ->
                showDialogFragment(
                    SleepTimerDialog.newInstance(
                        AudioPlayService.timeMinute,
                        AudioPlayService.chapterToStop,
                        useEpisodes = true,
                    )
                )
        }
    }

    private fun seekToLyric(position: Int) {
        AudioPlay.adjustProgress(position)
        playButton(false)
    }

    private fun playButton(noLyr: Boolean = true) {
        val status = AudioPlay.status
        when (status) {
            Status.PLAY if noLyr -> {
                AudioPlay.pause(this)
            }
            Status.PAUSE -> {
                AudioPlay.resume(this)
            }
            else -> {
                AudioPlay.loadOrUpPlayUrl()
            }
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc, onSuccess)
        } else {
            AudioPlay.stop()
            viewModel.changeToText(book, toc, onSuccess)
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()
        if (AudioPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf()
        } else {
            viewModel.update { copy(askShelf = true) }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(
            SourceCallBack.END_READ,
            AudioPlay.bookSource,
            AudioPlay.book,
            AudioPlay.durChapter,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            if (AudioPlay.callback === this) AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                viewModel.update { copy(playing = true) }
            } else {
                viewModel.update { copy(playing = false) }
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            viewModel.snapshot()
            viewModel.update {
                copy(
                    subtitle = it,
                    chapterIndex = AudioPlay.durChapterIndex,
                    chapterCount = AudioPlay.simulatedChapterSize,
                )
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) { size ->
            viewModel.update { copy(duration = size) }
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) { progress ->
            viewModel.update { copy(progress = progress) }
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) { buffer ->
            viewModel.update { copy(buffer = buffer) }
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) { speed ->
            viewModel.update { copy(speed = speed) }
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) { upTimerText() }
        observeEventSticky<Int>(EventBus.AUDIO_CHAPTER_STOP) { upTimerText() }
    }

    private fun upTimerText() {
        val chapter = AudioPlayService.chapterToStop
        val minute = AudioPlayService.timeMinute
        viewModel.update { copy(timerMinutes = minute, timerChapters = chapter) }
    }

    override fun onSleepTimerMinute(minute: Int) {
        AudioPlay.setTimer(minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        AudioPlay.setChapterStop(count)
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            viewModel.update { copy(loading = loading) }
        }
    }
}
