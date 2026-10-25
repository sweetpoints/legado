package io.legado.app.ui.about
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsViewportEpochTest {
    @Test fun oldScrollQueuedAfterNextCannotRestoreThePreviousPage() {
        val epoch = DiagnosticsViewportEpoch()
        val queuedScroll = epoch.value
        epoch.retire()
        var page = 1
        if(epoch.accepts(queuedScroll)) page = 0
        assertEquals(1, page)
        assertTrue(epoch.accepts(epoch.value))
    }
    @Test fun livePrependKeepsCurrentViewportReceiptValidAcrossPageBoundary() {
        val epoch = DiagnosticsViewportEpoch()
        val receipt = epoch.value
        val old = (0..249).map { "synthetic-$it" }
        val checkpoint = DiagnosticsViewportCheckpoint(1, 96, 17.0, DiagnosticsViewport.anchor(old[196]))
        val projection = DiagnosticsViewport.project((1..5).map { "new-$it" } + old, checkpoint, true)
        assertEquals(2, projection.page)
        assertTrue(epoch.accepts(receipt))
    }
}
