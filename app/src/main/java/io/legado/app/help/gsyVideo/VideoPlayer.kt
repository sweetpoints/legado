package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.shuyu.gsyvideoplayer.listener.LockClickListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoComposeTheme
import java.io.File
import java.io.FileInputStream
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.IllegalDataException
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

class VideoPlayer : StandardGSYVideoPlayer {
    constructor(
        context: Context?,
        fullFlag: Boolean?,
    ) : super(context, fullFlag) // 必须的,全屏时依靠这个构建知道获取全屏布局

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    private var gestureFeedbackDialog: VideoFeedbackDialog? = null
    private var networkConfirmationDialog: VideoNetworkDialog? = null
    private var activeChoiceDialog: VideoChoiceDialog? = null
    private val playbackPromptFence = VideoPlaybackPromptFence()

    override fun showWifiDialog() {
        if (!com.shuyu.gsyvideoplayer.utils.NetworkUtils.isAvailable(context)) {
            startPlayLogic()
            return
        }
        networkConfirmationDialog?.dismiss()
        val ticket = playbackPromptFence.capture(mOriginUrl)
        val dialog =
            VideoNetworkDialog(context) {
                // A delayed confirmation must not start a replacement stream or a released player.
                if (playbackPromptFence.accepts(ticket, mOriginUrl, isAttachedToWindow)) {
                    startPlayLogic()
                }
            }
        networkConfirmationDialog = dialog
        dialog.setOnDismissListener {
            if (networkConfirmationDialog === dialog) {
                networkConfirmationDialog = null
            }
        }
        dialog.show()
    }

    private fun dismissNetworkConfirmation() {
        networkConfirmationDialog?.dismiss()
        networkConfirmationDialog = null
    }

    private fun showOwnedChoiceDialog(dialog: VideoChoiceDialog) {
        activeChoiceDialog?.dismiss()
        activeChoiceDialog = dialog
        dialog.setOnDismissListener {
            if (activeChoiceDialog === dialog) {
                activeChoiceDialog = null
            }
        }
        dialog.show()
    }

    private fun dismissPlayerDialogs() {
        // Window callbacks belong to this player, never to a later stream or the transfer target.
        activeChoiceDialog?.dismiss()
        activeChoiceDialog = null
        dismissNetworkConfirmation()
        dismissGestureFeedback()
    }

    private fun showGestureFeedback(label: String, fraction: Float) {
        val dialog =
            gestureFeedbackDialog
                ?: VideoFeedbackDialog(context).also {
                    gestureFeedbackDialog = it
                }
        dialog.feedback = VideoFeedbackState(label, fraction)
        dialog.showOver(this)
    }

    private fun dismissGestureFeedback() {
        // Each native player owns its window, including the separate GSY fullscreen clone.
        gestureFeedbackDialog?.dismiss()
        gestureFeedbackDialog = null
    }

    override fun showProgressDialog(
        deltaX: Float,
        seekTime: String?,
        seekTimePosition: Long,
        totalTime: String?,
        totalTimeDuration: Long,
    ) {
        val direction = if (deltaX >= 0f) "快进" else "快退"
        val progress =
            if (totalTimeDuration > 0) {
                seekTimePosition.toFloat() / totalTimeDuration
            } else {
                0f
            }
        showGestureFeedback("$direction $seekTime / $totalTime", progress)
    }

    override fun dismissProgressDialog() {
        dismissGestureFeedback()
    }

    override fun showVolumeDialog(deltaY: Float, volumePercent: Int) {
        showGestureFeedback("音量 $volumePercent%", volumePercent / 100f)
    }

    override fun dismissVolumeDialog() {
        dismissGestureFeedback()
    }

    override fun showBrightnessDialog(percent: Float) {
        val brightnessPercent = (percent * 100).toInt().coerceIn(0, 100)
        showGestureFeedback("亮度 $brightnessPercent%", percent)
    }

    override fun dismissBrightnessDialog() {
        dismissGestureFeedback()
    }

    private var actionControlsState by mutableStateOf(VideoPlayerActionControlsState())
    private var playSpeed: Float = 1.0f
    private var tipView: TextView? = null
    private var isChanging = false
    private var isLongPressSpeed = false

    private var mParser: BaseDanmakuParser? = null // 解析器对象
    private var mDanmakuView: DanmakuView? = null // 弹幕view
    private var mDanmakuContext: DanmakuContext? = null
    var mToggleDanmaku: TextView? = null // 弹幕开关
    private var mDanmakuStartSeekPosition: Long = -1

    override fun getLayoutId(): Int {
        return if (mIfCurrentIsFullscreen) R.layout.video_layout_controller_full
        else R.layout.video_layout_controller
    }

    override fun getFullWindowPlayer(): VideoPlayer? {
        val activity = CommonUtil.scanForActivity(context) ?: return null
        val vp = activity.findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val full = vp.findViewById<View?>(fullId)
        var gsyVideoPlayer: VideoPlayer? = null
        if (full != null) {
            gsyVideoPlayer = full as VideoPlayer
        }
        return gsyVideoPlayer
    }

    override fun getSmallWindowPlayer(): VideoPlayer? = null

    override fun getCurrentPlayer(): VideoPlayer {
        val fullVideoPlayer = getFullWindowPlayer()
        if (fullVideoPlayer != null) {
            return fullVideoPlayer
        }
        val smallVideoPlayer = getSmallWindowPlayer()
        if (smallVideoPlayer != null) {
            return smallVideoPlayer
        }
        return this
    }

    fun getLockCurScreen() = mLockCurScreen

    public override fun lockTouchLogic() = super.lockTouchLogic()

    override fun init(context: Context) {
        super.init(context)
        initView()
        post {
            gestureDetector =
                GestureDetector(
                    getContext().applicationContext,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            touchDoubleUp(e)
                            return super.onDoubleTap(e)
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (
                                !mChangePosition &&
                                    !mChangeVolume &&
                                    !mBrightness &&
                                    mCurrentState != CURRENT_STATE_ERROR
                            ) {
                                onClickUiToggle(e)
                            }
                            return super.onSingleTapConfirmed(e)
                        }

                        override fun onLongPress(e: MotionEvent) {
                            if (mCurrentState == CURRENT_STATE_PLAYING) {
                                val speed = VideoPlay.longPressSpeed / 10.0f
                                setVideoSpeed(speed)
                                showOverlayTip("${speed}倍速播放中")
                                isLongPressSpeed = true
                            }
                            super.onLongPress(e)
                        }
                    },
                )
            mLockClickListener = LockClickListener { view, lock ->
                VideoPlay.lockCurScreen = lock
            }
        }
    }

    override fun touchSurfaceUp() {
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            setVideoSpeed(playSpeed)
            showOverlayTip()
            val time = getCurrentPositionWhenPlaying()
            resolveDanmakuStart(time)
        }
        super.touchSurfaceUp()
    }

    private fun setVideoSpeed(speed: Float) {
        setSpeed(speed, true)
        if (mDanmakuView != null && !mDanmakuView!!.isPaused) {
            mDanmakuContext!!.setScrollSpeedFactor(VideoPlay.danmakuSpeed - (speed - 1f) / 6f)
            mDanmakuView!!.invalidate()
        }
    }

    override fun onPrepared() {
        super.onPrepared()
        onPrepareDanmaku(this)
    }

    private fun onPrepareDanmaku(gsyVideoPlayer: VideoPlayer) {
        val view = gsyVideoPlayer.mDanmakuView
        val par = gsyVideoPlayer.mParser
        val con = gsyVideoPlayer.mDanmakuContext
        if (view != null && !view.isPrepared && par != null) {
            view.prepare(par, con)
        }
    }

    override fun onVideoPause() {
        super.onVideoPause()
        danmakuOnPause()
    }

    fun danmakuOnPause() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.pause()
        }
    }

    override fun onVideoResume(isResume: Boolean) {
        super.onVideoResume(isResume)
        danmakuOnResume()
    }

    fun danmakuOnResume() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared && mDanmakuView!!.isPaused) {
            mDanmakuView!!.resume()
        }
    }

    override fun clickStartIcon() {
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            danmakuOnPause()
        }
    }

    override fun onAutoCompletion() { // 播放完成
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        super.onAutoCompletion()
        VideoPlay.upDurIndex(1, this)
    }

    override fun onCompletion() {
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        super.onCompletion()
        releaseDanmaku(this)
    }

    fun releaseDanmaku(gsyVideoPlayer: VideoPlayer) {
        gsyVideoPlayer.mDanmakuView?.release()
    }

    override fun onSeekComplete() {
        super.onSeekComplete()
        val time = mProgressBar.progress * getDuration() / 100
        // 如果已经初始化过的，直接seek到对于位置
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            resolveDanmakuSeek(time)
        } else if (mHadPlay && mDanmakuView != null && !mDanmakuView!!.isPrepared) {
            // 如果没有初始化过的，记录位置等待
            mDanmakuStartSeekPosition = time
        }
    }

    fun showOverlayTip(message: String? = null, delay: Long = 0) {
        tipView?.apply {
            message?.also {
                text = it
                visibility = VISIBLE
                alpha = 1f
                if (delay > 0) {
                    postDelayed(
                        {
                            alpha = 0f
                        },
                        delay,
                    )
                }
            }
                ?: run {
                    visibility = INVISIBLE
                    alpha = 0f
                }
        }
    }

    private fun initView() {
        isNeedLockFull = true // 使用锁定按钮
        setupActionControls()
        tipView = findViewById(R.id.tip_view)
        if (mIfCurrentIsFullscreen && !VideoPlay.fullBottomProgressBar) {
            mBottomProgressBar = null
        }
        // 切换选集
        actionControlsState =
            actionControlsState.copy(episodeControlsVisible = VideoPlay.episodes != null)
        if (VideoPlay.episodes == null) {
            return
        }
    }

    private fun setupActionControls() {
        val controls = findViewById<ComposeView>(R.id.video_actions_compose) ?: return
        controls.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        controls.setContent {
            LegadoComposeTheme {
                VideoPlayerActionControls(
                    state = actionControlsState,
                    onNext = { VideoPlay.upDurIndex(1, this) },
                    onToggleDanmaku = {
                        VideoPlay.danmakuShow = !VideoPlay.danmakuShow
                        resolveDanmakuShow()
                    },
                    onOpenEpisodes = {
                        if (mHadPlay && !isChanging) showEpisodeDialog()
                    },
                    onOpenSpeed = {
                        if (mHadPlay && !isChanging) showSpeedDialog()
                    },
                )
            }
        }
    }

    override fun setUp(
        url: String?,
        cacheWithPlay: Boolean,
        cachePath: File?,
        title: String?,
    ): Boolean {
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        initDanmaku()
        return super.setUp(url, cacheWithPlay, cachePath, title)
    }

    private fun initDanmaku() {
        val danmakuFile = VideoPlay.danmakuFile
        val danmakuStr = VideoPlay.danmakuStr
        if (danmakuFile == null && danmakuStr.isNullOrBlank()) {
            mToggleDanmaku?.visibility = GONE
            actionControlsState = actionControlsState.copy(danmakuVisible = false)
            return
        }
        mDanmakuView =
            findViewById<DanmakuView>(R.id.danmaku_view)?.also {
                it.visibility = VISIBLE
            }
        // 弹幕开关
        mToggleDanmaku =
            findViewById<TextView>(R.id.toggle_danmaku)?.also {
                it.visibility = VISIBLE
                it.setOnClickListener { // 按钮事件
                    VideoPlay.danmakuShow = !VideoPlay.danmakuShow
                    resolveDanmakuShow()
                }
            }
        actionControlsState =
            actionControlsState.copy(
                danmakuVisible = true,
                danmakuEnabled = VideoPlay.danmakuShow,
            )
        if (mDanmakuView != null) {
            // 设置最大显示行数
            val maxLinesPair = HashMap<Int?, Int?>()
            maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 5 // 滚动弹幕最大显示5行
            // 设置是否禁止重叠
            val overlappingEnablePair = HashMap<Int?, Boolean?>()
            overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
            overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true
            val danmakuAdapter = DanmakuAdapter(mDanmakuView)
            mDanmakuContext = DanmakuContext.create() // 初始化上下文
            mDanmakuContext!!
                .setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f) // 设置弹幕类型
                .setDuplicateMergingEnabled(false) // 设置是否合并重复弹幕
                .setScrollSpeedFactor(VideoPlay.danmakuSpeed) // 设置弹幕滚动速度
                .setScaleTextSize(1.0f) // 设置弹幕字体大小
                .setCacheStuffer(
                    SpannedCacheStuffer(),
                    danmakuAdapter,
                ) // 设置缓存绘制填充器 图文混排使用SpannedCacheStuffer
                .setMaximumLines(maxLinesPair) // 设置最大行数
                .preventOverlapping(overlappingEnablePair) // 设置是否禁止重叠
            mParser = createParser(danmakuFile, danmakuStr) // 加载弹幕资源文件
            mDanmakuView!!.setCallback(
                object : DrawHandler.Callback {
                    override fun updateTimer(timer: DanmakuTimer?) {}

                    override fun drawingFinished() {}

                    override fun danmakuShown(danmaku: BaseDanmaku?) {}

                    override fun prepared() {
                        if (mDanmakuView != null) {
                            mDanmakuView!!.start()
                            if (mDanmakuStartSeekPosition != -1L) {
                                resolveDanmakuSeek(mDanmakuStartSeekPosition)
                                mDanmakuStartSeekPosition = -1L
                            }
                            resolveDanmakuShow()
                        }
                    }
                }
            )
            mDanmakuView!!.enableDanmakuDrawingCache(true)
        }
    }

    /** 弹幕偏移 */
    private fun resolveDanmakuSeek(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }

    private fun resolveDanmakuStart(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }

    private fun resolveDanmakuShow() {
        post {
            if (VideoPlay.danmakuShow) {
                if (!mDanmakuView!!.isShown) mDanmakuView!!.show()
                mToggleDanmaku?.setText(R.string.video_danmaku_disable)
            } else {
                if (mDanmakuView!!.isShown) mDanmakuView!!.hide()
                mToggleDanmaku?.setText(R.string.video_danmaku_enable)
            }
            actionControlsState = actionControlsState.copy(danmakuEnabled = VideoPlay.danmakuShow)
        }
    }

    /**
     * 创建解析器对象，解析输入流
     *
     * @param stream
     * @return
     */
    private fun createParser(danmakuFile: File?, danmakuStr: String?): BaseDanmakuParser {
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        try {
            if (danmakuFile != null) {
                loader.load(FileInputStream(danmakuFile))
            } else if (danmakuStr != null) {
                if (danmakuStr.startsWith("http", true)) {
                    loader.load(danmakuStr)
                } else {
                    loader.load(danmakuStr.byteInputStream())
                }
            }
        } catch (e: IllegalDataException) {
            e.printStackTrace()
        }
        val parser: BaseDanmakuParser = BiliDanmukuParser()
        val dataSource = loader.dataSource
        parser.load(dataSource)
        return parser
    }

    private fun showEpisodeDialog() {
        val episodes = VideoPlay.episodes ?: return
        if (!mHadPlay || episodes.isEmpty()) return
        val ticket = playbackPromptFence.capture(mOriginUrl)
        isChanging = true
        val choiceEpisodeDialog = ChoiceEpisodeDialog(mContext)
        choiceEpisodeDialog.initList(
            episodes,
            object : ChoiceEpisodeDialog.OnListItemClickListener {
                override fun onItemClick(position: Int) {
                    if (!isCurrentMediaListener || !mHadPlay || VideoPlay.episodes !== episodes)
                        return
                    if (!playbackPromptFence.accepts(ticket, mOriginUrl, isAttachedToWindow)) return
                    VideoPlay.chapterInVolumeIndex = position
                    VideoPlay.saveRead(0)
                    VideoPlay.startPlay(this@VideoPlayer)
                }

                override fun finishDialog() {
                    isChanging = false
                }
            },
            VideoPlay.chapterInVolumeIndex,
        )
        showOwnedChoiceDialog(choiceEpisodeDialog)
    }

    private fun showSpeedDialog() {
        if (!mHadPlay) {
            return
        }
        val ticket = playbackPromptFence.capture(mOriginUrl)
        isChanging = true
        val choiceSpeedDialog = ChoiceSpeedDialog(mContext)
        choiceSpeedDialog.initList(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).reversed(),
            object : ChoiceSpeedDialog.OnListItemClickListener {
                @SuppressLint("SetTextI18n")
                override fun onItemClick(value: Float) {
                    if (!isCurrentMediaListener || !mHadPlay) return
                    if (!playbackPromptFence.accepts(ticket, mOriginUrl, isAttachedToWindow)) return
                    playSpeed = value
                    setSpeed(playSpeed, true)
                    actionControlsState =
                        actionControlsState.copy(selectedSpeed = value.takeUnless { it == 1.0f })
                    if (playSpeed != 1.0f) {
                        showOverlayTip("${playSpeed}倍播放中", 2000)
                    }
                }

                override fun finishDialog() {
                    isChanging = false
                }
            },
        )
        showOwnedChoiceDialog(choiceSpeedDialog)
    }

    override fun updateStartImage() {
        if (mIfCurrentIsFullscreen) {
            if (mStartButton is ImageView) {
                val imageView = mStartButton as ImageView
                when (mCurrentState) {
                    CURRENT_STATE_PLAYING -> {
                        imageView.setImageResource(R.drawable.ic_pause_24dp)
                    }
                    CURRENT_STATE_ERROR -> {
                        imageView.setImageResource(R.drawable.ic_pause_outline_24dp)
                    }
                    else -> {
                        imageView.setImageResource(R.drawable.ic_play_24dp)
                    }
                }
            }
        } else {
            super.updateStartImage()
        }
    }

    override fun onError(what: Int, extra: Int) {
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        super.onError(what, extra)
        VideoPlay.saveRead()
        mSeekOnStart = VideoPlay.durChapterPos.toLong()
    }

    /** 处理播放器在全屏切换时，弹幕显示的逻辑 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态 */
    override fun startWindowFullscreen(
        context: Context?,
        actionBar: Boolean,
        statusBar: Boolean,
    ): VideoPlayer? {
        val gsyBaseVideoPlayer = super.startWindowFullscreen(context, actionBar, statusBar)
        if (gsyBaseVideoPlayer != null) {
            val gsyVideoPlayer = gsyBaseVideoPlayer as VideoPlayer
            // 对弹幕设置偏移记录
            //            gsyVideoPlayer.mDanmakuView = this.mDanmakuView
            gsyVideoPlayer.mDanmakuStartSeekPosition = this.getCurrentPositionWhenPlaying()
            onPrepareDanmaku(gsyVideoPlayer)
        }
        return gsyBaseVideoPlayer
    }

    /** 处理播放器在退出全屏时，弹幕显示的逻辑 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态 */
    override fun resolveNormalVideoShow(
        oldF: View?,
        vp: ViewGroup?,
        gsyVideoPlayer: GSYVideoPlayer?,
    ) {
        super.resolveNormalVideoShow(oldF, vp, gsyVideoPlayer)
        if (gsyVideoPlayer != null) {
            val videoPlayer = gsyVideoPlayer as VideoPlayer
            if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
                resolveDanmakuSeek(videoPlayer.getCurrentPositionWhenPlaying())
                resolveDanmakuShow()
                releaseDanmaku(videoPlayer)
            }
        }
    }

    override fun onDetachedFromWindow() {
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        super.onDetachedFromWindow()
    }

    override fun release() {
        playbackPromptFence.invalidate()
        dismissPlayerDialogs()
        super.release()
        releaseDanmaku(this)
    }

    /** ********以下重载GSYVideoPlayer的GSYVideoViewBridge相关实现********** */
    override fun getGSYVideoManager(): ExoVideoManager {
        return VideoPlay.videoManager.apply { initContext(context.applicationContext) }
    }

    public override fun backFromFull(context: Context?): Boolean {
        return VideoPlay.backFromWindowFull(context)
    }

    override fun releaseVideos() {
        VideoPlay.releaseAllVideos()
    }

    override fun getFullId(): Int {
        return ExoVideoManager.FULLSCREEN_ID
    }

    override fun getSmallId(): Int {
        return ExoVideoManager.SMALL_ID
    }

    override fun setDisplay(surface: Surface?) {
        if (surface != null && mTextureView.getShowView() is SurfaceView) {
            val surfaceView = (mTextureView.getShowView() as SurfaceView?)
            gsyVideoManager.setDisplayNew(surfaceView)
        } else if (surface != null) {
            gsyVideoManager.setDisplay(surface)
        } else {
            gsyVideoManager.setDisplayNew(null)
        }
    }

    fun nextUI() {
        resetProgressAndTime()
    }

    // 播放器转移
    fun setSurfaceToPlay() {
        addTextureView()
        gsyVideoManager.setListener(this)
        checkoutState()
    }

    var needDestroy: Boolean = true

    override fun onSurfaceDestroyed(surface: Surface?): Boolean {
        if (needDestroy) {
            return super.onSurfaceDestroyed(surface)
        } else {
            releaseSurface(surface)
            needDestroy = true
            return true
        }
    }

    fun saveState(): VideoPlayer {
        return this
    }

    fun cloneState(switchVideo: StandardGSYVideoPlayer) {
        cloneParams(switchVideo, this)
    }
}
