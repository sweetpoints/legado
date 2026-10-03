package io.legado.app.ui.book.audio

import io.legado.app.model.releaseAudioController
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioControllerOwnershipTest {
    @Test
    fun retiredHostCannotCancelReplacementRequests() {
        val retiredHost = Any()
        val replacement = Any()
        val replacementRequests = Job()
        assertFalse(
            releaseAudioController(replacement, retiredHost) { replacementRequests.cancel() }
        )
        assertTrue(replacementRequests.isActive)
        assertTrue(
            releaseAudioController(replacement, replacement) { replacementRequests.cancel() }
        )
        assertTrue(replacementRequests.isCancelled)
    }
}
