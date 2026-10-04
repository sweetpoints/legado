package io.legado.app.help

import android.content.Context
import android.content.ComponentName
import android.os.Build
import android.media.AudioManager
import android.media.MediaPlayer
import android.app.Notification
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.utils.broadcastPendingIntent
import android.content.Intent
import splitties.systemservices.audioManager

object MediaHelp {
    const val MEDIA_SESSION_ACTIONS = PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_REWIND or PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_SET_RATING or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
        PlaybackState.ACTION_PLAY_FROM_URI or PlaybackState.ACTION_PREPARE or
        PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackState.ACTION_PREPARE_FROM_SEARCH or
        PlaybackState.ACTION_PREPARE_FROM_URI


    fun buildAudioFocusRequest(
        audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener
    ): AudioFocusRequest {
        val mPlaybackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mPlaybackAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }


    /**
     * @return 音频焦点
     */
    fun requestFocus(focusRequest: AudioFocusRequest): Boolean {
        val request = audioManager.requestAudioFocus(focusRequest)
        return request == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION") // PendingIntent receiver is needed only on API 26-30.
    fun setMediaButtonReceiver(context: Context, session: MediaSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            session.setMediaButtonBroadcastReceiver(ComponentName(context, MediaButtonReceiver::class.java))
        } else {
            session.setMediaButtonReceiver(context.broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON))
        }
    }

    fun mediaNotification(
        context: Context,
        builder: NotificationCompat.Builder,
        session: MediaSession,
    ): Notification =
        Notification.Builder.recoverBuilder(context, builder.build())
            .setStyle(Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(session.sessionToken))
            .build()

    /**
     * 播放静音音频,用来获取音频焦点
     */
    fun playSilentSound(mContext: Context) {
        kotlin.runCatching {
            // Stupid Android 8 "Oreo" hack to make media buttons work
            val mMediaPlayer = MediaPlayer.create(mContext, R.raw.silent_sound)
            mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
            mMediaPlayer.start()
        }
    }
}
