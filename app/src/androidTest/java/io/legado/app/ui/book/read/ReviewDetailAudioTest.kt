package io.legado.app.ui.book.read

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReviewDetailAudioTest {
    @Test fun repeatedPreparingClickDoesNotRestartAndReadyTogglePausesThenResumesAndReleaseDisposesOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fake = FakePlayer(); val controller = ReviewDetailAudio({ fake.player }, { error(it) })
            controller.toggle("audio", MediaItem.fromUri("https://audio")); controller.toggle("audio", null)
            assertEquals(1, fake.calls.count { it == "prepare" }); assertTrue(controller.state.value.preparing)
            fake.listener!!.onPlaybackStateChanged(Player.STATE_READY); fake.listener!!.onIsPlayingChanged(true); fake.playing = true
            controller.toggle("audio", null); assertFalse(controller.state.value.playing); assertEquals(1, fake.calls.count { it == "pause" })
            controller.toggle("audio", null); assertTrue(controller.state.value.playing)
            fake.listener!!.onPlaybackStateChanged(Player.STATE_ENDED); assertNull(controller.state.value.url)
            controller.release(); controller.release(); assertEquals(1, fake.calls.count { it == "release" })
            assertNull(fake.listener); assertNull(controller.state.value.url)
        }
    }
    @Test fun aspectCacheAcceptsOnlyValidIntrinsicDimensionsAndKeepsRatioDuringReload() {
        val cache = ReviewImageDimensions(); assertNull(cache.ratio("image"))
        cache.update("image", 640f, 320f); assertEquals(2f, cache.ratio("image"))
        cache.update("image", 0f, 320f); cache.update("image", 640f, -1f)
        assertEquals(2f, cache.ratio("image")); assertNull(cache.ratio("other"))
    }
    private class FakePlayer {
        var listener: Player.Listener? = null; var playing = false; val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "removeListener" -> { listener = null; null }
                "isPlaying" -> playing
                "pause" -> { playing = false; null }
                "play" -> { playing = true; null }
                "equals" -> false
                "hashCode" -> 1
                "toString" -> "FakePlayer"
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; else -> null }
            }
        } as Player
    }
}
