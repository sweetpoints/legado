package io.legado.app.help.gsyVideo

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isNotEmpty
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoComposeTheme

class FloatingPlayer : StandardGSYVideoPlayer {
    constructor(context: Context, fullFlag: Boolean) : super(context, fullFlag) {
        initializeComposeControls()
    }

    constructor(context: Context) : super(context) {
        initializeComposeControls()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initializeComposeControls()
    }

    var onCloseRequested: (() -> Unit)? = null
    var onFullscreenRequested: (() -> Unit)? = null
    private var controlsState by mutableStateOf(FloatingPlayerControlsState())

    override fun init(context: Context?) {
        if (activityContext != null) {
            this.mContext = activityContext
        } else {
            this.mContext = context
        }
        initInflate(mContext)
        mTextureViewContainer = findViewById(R.id.surface_container)
        if (isInEditMode) return
        mScreenWidth = activityContext!!.resources.displayMetrics.widthPixels
        mScreenHeight = activityContext!!.resources.displayMetrics.heightPixels
    }

    override fun getLayoutId(): Int {
        return R.layout.floating_player_surface
    }

    private fun initializeComposeControls() {
        val controls = findViewById<ComposeView>(R.id.floating_player_compose) ?: return
        controls.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        controls.setContent {
            LegadoComposeTheme {
                FloatingPlayerControls(
                    state = controlsState,
                    onClose = { onCloseRequested?.invoke() },
                    onFullscreen = { onFullscreenRequested?.invoke() },
                    onPlayback = ::clickStartIcon,
                )
            }
        }
    }

    override fun resolveUIState(state: Int) {
        super.resolveUIState(state)
        controlsState =
            controlsState.copy(
                controlsVisible = true,
                playing = state == CURRENT_STATE_PLAYING,
            )
    }

    override fun hideAllWidget() {
        super.hideAllWidget()
        controlsState = controlsState.copy(controlsVisible = false)
    }

    override fun onAutoCompletion() { // 自动播放完成
        setStateAndUi(CURRENT_STATE_AUTO_COMPLETE)
        mSaveChangeViewTIme = 0
        if (mTextureViewContainer.isNotEmpty()) {
            mTextureViewContainer.removeAllViews()
        }
        if (!mIfCurrentIsFullscreen) gsyVideoManager.setLastListener(null)
        releaseNetWorkState()
        if (mVideoAllCallBack != null && isCurrentMediaListener) {
            mVideoAllCallBack.onAutoComplete(mOriginUrl, mTitle, this)
        }
    }

    override fun onCompletion() {
        setStateAndUi(CURRENT_STATE_NORMAL)
        mSaveChangeViewTIme = 0
        if (mTextureViewContainer.isNotEmpty()) {
            mTextureViewContainer.removeAllViews()
        }
        if (!mIfCurrentIsFullscreen) {
            gsyVideoManager.setListener(null)
            gsyVideoManager.setLastListener(null)
        }
        gsyVideoManager.currentVideoHeight = 0
        gsyVideoManager.currentVideoWidth = 0
        releaseNetWorkState()
    }

    override fun getActivityContext(): Context? {
        return context
    }

    override fun isShowNetConfirm(): Boolean {
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun setProgressAndTime(
        progress: Long,
        secProgress: Long,
        currentTime: Long,
        totalTime: Long,
        forceChange: Boolean,
    ) {
        super.setProgressAndTime(progress, secProgress, currentTime, totalTime, forceChange)
        if (mHadSeekTouch) {
            return
        }
        controlsState =
            controlsState.copy(
                progress =
                    if (progress >= 0L || forceChange) (progress / 100f).coerceIn(0f, 1f)
                    else controlsState.progress,
                playing = mCurrentState == CURRENT_STATE_PLAYING,
            )
    }

    fun showControlUi() {
        if (controlsState.controlsVisible) {
            hideAllWidget()
        } else {
            resolveUIState(mCurrentState)
        }
    }

    override fun getFullWindowPlayer(): GSYVideoPlayer? = null

    override fun getSmallWindowPlayer(): GSYVideoPlayer? = null

    override fun onError(what: Int, extra: Int) {
        // The hidden GSY lock button is no longer part of the floating Compose host.
        mLockCurScreen = false
        VideoPlay.lockCurScreen = false
        super.onError(what, extra)
        VideoPlay.saveRead()
        mSeekOnStart = VideoPlay.durChapterPos.toLong()
    }

    override fun getCurrentPlayer(): FloatingPlayer {
        return this
    }

    /** ********以下重载GSYVideoPlayer的GSYVideoViewBridge相关实现********** */
    override fun getGSYVideoManager(): ExoVideoManager {
        return VideoPlay.videoManager.apply { initContext(context.applicationContext) }
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
        controlsState = controlsState.copy(progress = 0f)
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

    fun saveState(): FloatingPlayer {
        return this
    }

    fun cloneState(switchVideo: StandardGSYVideoPlayer) {
        cloneParams(switchVideo, this)
    }
}
