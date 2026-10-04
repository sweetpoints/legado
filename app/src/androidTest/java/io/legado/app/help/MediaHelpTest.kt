package io.legado.app.help

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaHelpTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun platformSessionPublishesMetadataAndReceivesTransportCommands() {
        val session = MediaSession(context, "media-regression")
        try {
            val played = CountDownLatch(1)
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { played.countDown() }
            }, Handler(Looper.getMainLooper()))
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "test chapter").build())
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
            session.isActive = true
            assertEquals("test chapter", session.controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            session.controller.transportControls.play()
            assertTrue("Media controller play must reach the session callback", played.await(5, TimeUnit.SECONDS))
        } finally { session.release() }
    }

    @Test
    fun mediaNotificationPreservesItsActionsAndSessionToken() {
        val session = MediaSession(context, "notification-regression")
        try {
            val builder = NotificationCompat.Builder(context, "regression")
                .setSmallIcon(R.drawable.ic_play_24dp)
                .setContentTitle("test chapter")
                .addAction(R.drawable.ic_skip_previous, "previous", null)
                .addAction(R.drawable.ic_play_24dp, "play", null)
                .addAction(R.drawable.ic_skip_next, "next", null)
            val notification = MediaHelp.mediaNotification(context, builder, session)
            assertEquals(3, notification.actions.size)
            assertEquals(session.sessionToken, BundleCompat.getParcelable(
                notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
            assertEquals("test chapter", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        } finally { session.release() }
    }
}
