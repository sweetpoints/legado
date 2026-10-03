package io.legado.app.ui.book.read

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.legado.app.help.exoplayer.ExoPlayerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Player owns platform state on the main thread; neither VM nor SavedState retains it. */
internal class ReviewDetailAudio(
    private val createPlayer: () -> Player,
    private val onError: (String) -> Unit,
) {
    constructor(
        context: Context,
        onError: (String) -> Unit,
    ) : this(
        { ExoPlayerHelper.createHttpExoPlayer(context.applicationContext) },
        onError,
    )

    private var player: Player? = null
    private val mutable = MutableStateFlow(ReviewAudioState())
    val state = mutable.asStateFlow()
    private val listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                mutable.value =
                    when (playbackState) {
                        Player.STATE_BUFFERING -> state.value.copy(preparing = true)
                        Player.STATE_READY -> state.value.copy(preparing = false)
                        Player.STATE_ENDED -> ReviewAudioState()
                        else -> state.value
                    }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mutable.value = state.value.copy(playing = isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                mutable.value = ReviewAudioState()
                onError(error.localizedMessage.orEmpty())
            }
        }

    fun toggle(url: String, item: MediaItem?) {
        if (state.value.url == url) {
            if (state.value.preparing) return
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
                mutable.value = state.value.copy(playing = it.isPlaying)
            }
            return
        }
        if (item == null) return
        val target =
            player
                ?: createPlayer().also {
                    player = it
                    it.addListener(listener)
                }
        mutable.value = ReviewAudioState(url, preparing = true)
        try {
            target.setMediaItem(item, true)
            target.prepare()
            target.playWhenReady = true
        } catch (error: Exception) {
            mutable.value = ReviewAudioState()
            onError(error.localizedMessage.orEmpty())
        }
    }

    fun release() {
        mutable.value = ReviewAudioState()
        player?.let { target ->
            target.removeListener(listener)
            try {
                target.stop()
                target.clearMediaItems()
            } finally {
                target.release()
            }
        }
        player = null
    }
}
