package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.CoverRequest
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar

class VideoPlayerActivity :
    BaseComposeActivity(), SettingsDialog.CallBack, RssFavoritesDialog.Callback {
    private val viewModel by viewModels<VideoPlayerViewModel>()
    private lateinit var playerView: VideoPlayer
    private var chapterRailState by mutableStateOf(VideoChapterRailState())
    private var bookHeaderState by mutableStateOf(VideoBookHeaderState())
    private var coverRequest by mutableStateOf(CoverRequest())
    private var bookIntroState by mutableStateOf(VideoBookIntroState())
    private var toolbarState by mutableStateOf(VideoPlayerToolbarState())
    private var playerHeightPx by mutableIntStateOf(0)
    private var playerAspectRatio by mutableStateOf(9f / 16f)
    private var shouldRenderPlayer by mutableStateOf(false)
    private var isNew = true
    private var forwardedToFloatingWindow = false
    private var isFullScreen by mutableStateOf(false)
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val bookSourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource {
                    updateToolbarSourceActions()
                }
            }
        }
    private val rssSourceEditResult =
        registerForActivityResult(StartActivityContract(RssSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val tocActivityResult =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                if (it[2] as Boolean) {
                    VideoPlay.chapterInVolumeIndex = it[0] as Int
                    val durChapterPos = it[1] as Int
                    VideoPlay.durVolumeIndex = it[3] as Int
                    VideoPlay.chapterInVolumeIndex = it[4] as Int
                    VideoPlay.upEpisodes()
                    VideoPlay.saveRead(durChapterPos)
                    upView()
                    VideoPlay.startPlay(playerView)
                }
            }
        }

    @OptIn(UnstableApi::class)
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        isNew = intent.getBooleanExtra("isNew", true)
        if (
            isNew &&
                intent.action == null &&
                VideoPlay.defaultFloatWindow &&
                !intent.getBooleanExtra("forceNormalPlayer", false)
        ) {
            forwardedToFloatingWindow = true
            intent.putExtra("forwardedToFloatingWindow", true)
            ContextCompat.startForegroundService(
                this,
                Intent(intent).setClass(this, VideoPlayService::class.java),
            )
            super.finish()
            return
        }
        if (isNew) {
            intent.getStringExtra("videoUrl")?.let {
                VideoPlay.videoUrl = it
                VideoPlay.singleUrl = true
            }
            intent.getStringExtra("videoTitle")?.let {
                VideoPlay.videoTitle = it
            }
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
                finish()
                return
            }
        }
        if (isFinishing) return
        shouldRenderPlayer = true
        updatePlayerHeight()
        toolbarState = toolbarState.copy(title = VideoPlay.videoTitle.orEmpty())
        updateToolbarSourceActions()
        updateToolbarFavoriteAction()
        initView()
        upView()
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    private fun initView() {
        viewModel.upStarMenuData.observe(this) { updateToolbarFavoriteAction() }
        val book = VideoPlay.book
        if (book != null) {
            showBook(book)
        }
        updateChapterRail()
    }

    private fun showBook(book: Book) {
        bookHeaderState = VideoBookHeaderState(book.name, book.getRealAuthor())
        showCover(book)
        bookIntroState =
            VideoBookIntroState(
                rawIntro = book.getDisplayIntro().orEmpty(),
                bookUrl = book.bookUrl,
                source = VideoPlay.source,
            )
    }

    private fun showCover(book: Book) {
        coverRequest = CoverRequest.from(book)
    }

    private fun updatePlayerHeight() {
        val displayMetrics = resources.displayMetrics
        playerHeightPx =
            minOf(
                (displayMetrics.widthPixels * playerAspectRatio).toInt(),
                displayMetrics.heightPixels / 2,
            )
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        if (!shouldRenderPlayer) return
        VideoPlayerRoute(
            toolbarState = toolbarState,
            chapterState = chapterRailState,
            coverRequest = coverRequest,
            coverDescription = getString(R.string.img_cover),
            headerState = bookHeaderState,
            introState = bookIntroState,
            playerHeight = with(LocalDensity.current) { playerHeightPx.toDp() },
            backgroundColor = backgroundColor,
            showBookContent = VideoPlay.book != null,
            fullscreen = isFullScreen,
            onPlayerCreated = ::initializePlayer,
            onBack = { onBackPressedDispatcher.onBackPressed() },
            onCustomButton = ::onCustomToolbarButton,
            onFavorite = ::onFavoriteToolbarAction,
            onFloatingWindow = ::startFloatingWindow,
            onMenuExpandedChange = { expanded ->
                updateToolbarSourceActions()
                toolbarState = toolbarState.copy(menuExpanded = expanded)
            },
            onMenuAction = ::handleToolbarMenuAction,
            onVolumeSelected = ::selectVolume,
            onEpisodeSelected = ::selectEpisode,
            onOpenCatalog = {
                if (!VideoPlay.episodes.isNullOrEmpty()) {
                    VideoPlay.book?.bookUrl?.let { tocActivityResult.launch(it) }
                }
            },
            onIntroAction = { action ->
                val name = if (action.name == "image") "info image" else "info ${action.name}"
                viewModel.onButtonClick(this@VideoPlayerActivity, name, action.script)
            },
            onIntroLink = ::handleIntroLink,
            onIntroImage = { image ->
                showDialogFragment(PhotoDialog(image, bookIntroState.source?.getKey()))
            },
        )
    }

    private fun initializePlayer(player: VideoPlayer) {
        playerView = player
        playerView.enlargeImageRes = R.drawable.ic_fullscreen
        setupPlayerView()
        playerView.updateOverlayTitle(toolbarState.title)
        if (isNew) {
            VideoPlay.startPlay(playerView)
            VideoPlay.saveRead()
        } else {
            VideoPlay.clonePlayState(playerView)
            playerView.setSurfaceToPlay()
            playerView.startAfterPrepared()
        }
    }

    private fun updateToolbarSourceActions() {
        toolbarState =
            toolbarState.copy(
                customButtonVisible = (VideoPlay.source as? BookSource)?.customButton == true,
                loginActionVisible = VideoPlay.source?.hasLogin() == true,
            )
    }

    private fun updateToolbarFavoriteAction() {
        toolbarState =
            toolbarState.copy(
                favoriteActionVisible = VideoPlay.rssStar != null || VideoPlay.rssRecord != null,
                isFavorite = VideoPlay.rssStar != null,
            )
    }

    private fun onCustomToolbarButton() {
        (VideoPlay.source as? BookSource)?.let { source ->
            VideoPlay.book?.let { book ->
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    source,
                    book,
                    VideoPlay.chapter,
                    BookType.video,
                )
            }
        }
    }

    private fun onFavoriteToolbarAction() {
        viewModel.addFavorite {
            VideoPlay.rssStar?.let { showDialogFragment(RssFavoritesDialog(it)) }
        }
    }

    private fun handleToolbarMenuAction(action: VideoPlayerToolbarAction) {
        when (action) {
            VideoPlayerToolbarAction.Settings -> showDialogFragment(SettingsDialog(this))
            VideoPlayerToolbarAction.Login ->
                VideoPlay.source?.let { source ->
                    when (source) {
                        is BookSource -> {
                            startActivity<SourceLoginActivity> {
                                putExtra("bookType", BookType.video)
                            }
                        }
                        is RssSource -> {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "rssSource")
                                putExtra("key", source.getKey())
                            }
                        }
                    }
                }
            VideoPlayerToolbarAction.CopyVideoUrl -> copyVideoUrl()
            VideoPlayerToolbarAction.OpenOtherPlayer -> openInOtherVideoPlayer()
            VideoPlayerToolbarAction.EditSource -> editCurrentSource()
            VideoPlayerToolbarAction.OpenLog -> showDialogFragment<AppLogDialog>()
        }
    }

    private fun copyVideoUrl() {
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            toastOnUi("暂无播放地址")
            return
        }
        VideoPlay.book?.let { book ->
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_COPY_PLAY_URL,
                VideoPlay.source as? BookSource,
                book,
                VideoPlay.chapter,
                BookType.video,
                url,
            ) {
                sendToClip(url)
            }
        }
    }

    private fun openInOtherVideoPlayer() {
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            toastOnUi("暂无播放地址")
            return
        }
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(url.toUri(), "video/*")
            }
        startActivity(intent)
    }

    private fun editCurrentSource() {
        VideoPlay.source?.let { source ->
            when (source) {
                is BookSource ->
                    bookSourceEditResult.launch {
                        putExtra("sourceUrl", source.getKey())
                    }
                is RssSource ->
                    rssSourceEditResult.launch {
                        putExtra("sourceUrl", source.getKey())
                    }
            }
        }
    }

    private fun handleIntroLink(url: String) {
        val uri = url.toUri()
        when (uri.scheme) {
            "http",
            "https" -> openUrl(uri)
            "legado",
            "yuedu" -> {
                startActivity<OnLineImportActivity> {
                    data = uri
                }
            }

            else -> {
                window.decorView.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                    openUrl(uri)
                }
            }
        }
    }

    private fun updateChapterRail() {
        chapterRailState =
            VideoChapterRailState(
                volumes = VideoPlay.volumes.map { it.title },
                episodes = VideoPlay.episodes.orEmpty().map { it.title },
                selectedVolume = VideoPlay.durVolumeIndex,
                selectedEpisode = VideoPlay.chapterInVolumeIndex,
            )
    }

    private fun selectEpisode(index: Int) {
        if (index !in VideoPlay.episodes.orEmpty().indices) return
        if (index == VideoPlay.chapterInVolumeIndex) return
        VideoPlay.chapterInVolumeIndex = index
        VideoPlay.saveRead(0)
        updateChapterRail()
        VideoPlay.startPlay(playerView)
    }

    private fun selectVolume(index: Int) {
        if (index !in VideoPlay.volumes.indices) return
        if (index == VideoPlay.durVolumeIndex) return
        VideoPlay.durVolumeIndex = index
        VideoPlay.chapterInVolumeIndex = 0
        VideoPlay.upEpisodes()
        VideoPlay.saveRead(0)
        updateChapterRail()
        VideoPlay.startPlay(playerView)
    }

    private fun upView() {
        updateChapterRail()
    }

    private fun upEpisodesView() {
        updateChapterRail()
    }

    internal fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            orientation = requestedOrientation
            requestedOrientation =
                if (VideoPlay.isPortraitVideo) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT // 竖屏
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE // 横屏
                }
            playerView.startWindowFullscreen(this, false, false)
        } else {
            requestedOrientation = orientation
            playerView.postDelayed(
                {
                    playerView.backFromFull(this)
                },
                if (VideoPlay.isPortraitVideo) 300 else 0,
            )
            upView()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayerHeight()
        if (isFullScreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    if (!VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
                Configuration.ORIENTATION_PORTRAIT -> {
                    if (VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
            }
        }
    }

    private fun setupPlayerView() {
        playerView.isNeedOrientationUtils = false // 关闭自带的屏幕方向控制
        playerView.setBackFromFullScreenListener { toggleFullScreen() }
        playerView.setVideoAllCallBack(
            object : GSYSampleCallBack() {
                @SuppressLint("SourceLockedOrientationActivity")
                override fun onPrepared(url: String?, vararg objects: Any?) {
                    super.onPrepared(url, *objects)
                    playerView.post {
                        val player = playerView.getCurrentPlayer()
                        if (VideoPlay.lockCurScreen && !player.getLockCurScreen()) {
                            player.lockTouchLogic()
                        }
                        // 根据实际视频比例再次调整
                        val videoWidth = player.currentVideoWidth
                        val videoHeight = player.currentVideoHeight
                        if (videoWidth > 0 && videoHeight > 0) {
                            val parentWidth = playerView.width
                            val aspectRatio = videoHeight.toFloat() / videoWidth.toFloat()
                            val isPortraitVideo = if (aspectRatio > 1.2) true else false
                            VideoPlay.isPortraitVideo = isPortraitVideo
                            if (isFullScreen && isPortraitVideo) {
                                requestedOrientation =
                                    ActivityInfo
                                        .SCREEN_ORIENTATION_SENSOR_PORTRAIT // 提前进入了全屏，并且默认横屏了，纠正回来
                                return@post
                            }
                            if (VideoPlay.startFull && VideoPlay.autoPlay && !isFullScreen) {
                                toggleFullScreen()
                                return@post
                            }
                            // 高度不超过一半屏幕
                            if (parentWidth > 0) {
                                playerAspectRatio = aspectRatio
                                updatePlayerHeight()
                            }
                        }
                    }
                }
            }
        )
    }

    private fun startFloatingWindow() {
        VideoPlay.savePlayState(playerView)
        // 启动悬浮窗服务
        val intent =
            Intent(this, VideoPlayService::class.java).apply {
                putExtra("isNew", false)
            }
        ContextCompat.startForegroundService(this, intent)
        playerView.needDestroy = false
        finish() // 如果在播放器复刻前活动被销毁，会导致状态继承异常（这里服务创建很快，没发现异常）
    }

    override fun observeLiveBus() {

        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            toolbarState = toolbarState.copy(title = it)
            if (::playerView.isInitialized) {
                playerView.updateOverlayTitle(it)
            }
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
            it.forEach { value ->
                when (value) {
                    1 -> upEpisodesView()
                }
            }
        }
    }

    override fun finish() {
        val book = VideoPlay.book ?: return super.finish()
        if (VideoPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    VideoPlay.book?.removeType(BookType.notShelf)
                    VideoPlay.book?.save()
                    VideoPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(
            SourceCallBack.END_READ,
            VideoPlay.source as BookSource?,
            VideoPlay.book,
            VideoPlay.chapter,
        )
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.updateFavorite(title, group)
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!forwardedToFloatingWindow) {
            VideoPlay.saveRead()
            VideoPlay.stopLoading()
            if (::playerView.isInitialized) {
                playerView.getCurrentPlayer().release()
            }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
