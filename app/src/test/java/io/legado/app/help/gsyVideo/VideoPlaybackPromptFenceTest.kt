package io.legado.app.help.gsyVideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPromptFenceTest {
    @Test
    fun firstPlaybackCanBeConfirmedBeforeItHasAMediaManagerListener() {
        val fence = VideoPlaybackPromptFence()
        val ticket = fence.capture("https://media.example/episode")
        assertTrue(fence.accepts(ticket, "https://media.example/episode", attached = true))
    }

    @Test
    fun replacementUsingSameUrlRejectsEarlierConfirmation() {
        val fence = VideoPlaybackPromptFence()
        val ticket = fence.capture("https://media.example/episode")
        fence.invalidate()
        assertFalse(fence.accepts(ticket, "https://media.example/episode", attached = true))
        assertTrue(
            fence.accepts(
                fence.capture("https://media.example/episode"),
                "https://media.example/episode",
                attached = true,
            )
        )
    }

    @Test
    fun changedUrlDetachedSurfaceAndReleasedOwnerRejectConfirmation() {
        val fence = VideoPlaybackPromptFence()
        val ticket = fence.capture("https://media.example/first")
        assertFalse(fence.accepts(ticket, "https://media.example/second", attached = true))
        assertFalse(fence.accepts(ticket, "https://media.example/first", attached = false))
        fence.invalidate()
        assertFalse(fence.accepts(ticket, "https://media.example/first", attached = true))
    }
}
