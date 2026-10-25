package io.legado.app.ui.about

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsViewportTest {
    @Test fun pageOneCheckpointSurvivesNewOwnerLoadingAndLoadFailureBeforeSuccessfulList() {
        val saved = DiagnosticsViewportCheckpoint(1, 3, 17.5, null)
        val loading = DiagnosticsViewport.project(emptyList(), saved, settled = false)
        assertEquals(0, loading.page)
        assertEquals(saved, loading.checkpoint)
        val failure = DiagnosticsViewport.project(emptyList(), loading.checkpoint, settled = false)
        assertEquals(saved, failure.checkpoint)
        val loaded = DiagnosticsViewport.project((0..104).map { "synthetic-$it" }, failure.checkpoint, settled = true)
        assertEquals(1, loaded.page)
        assertEquals(saved, loaded.checkpoint)
    }

    @Test fun livePrependMovesTheBusinessAnchorAcrossTheHundredRowBoundary() {
        val old = (0..249).map { "synthetic-$it" }
        val saved = DiagnosticsViewportCheckpoint(1, 96, 19.5, DiagnosticsViewport.anchor(old[196]))
        val prepended = (1..5).map { "new-synthetic-$it" } + old
        val projected = DiagnosticsViewport.project(prepended, saved, settled = true)
        assertEquals(2, projected.page)
        assertEquals(1, projected.checkpoint.index)
        assertEquals(19.5, projected.checkpoint.offset, 0.0)
        assertEquals(saved.anchor, projected.checkpoint.anchor)
        assertEquals(old[196], prepended[projected.page * 100 + projected.checkpoint.index])
    }

    @Test fun settledEmptyListMayClampAnObsoleteCheckpoint() {
        val saved = DiagnosticsViewportCheckpoint(3, 7, 18.0, DiagnosticsViewport.anchor("gone"))
        val cleared = DiagnosticsViewport.project(emptyList(), saved, settled = true)
        assertEquals(DiagnosticsViewportCheckpoint(0, 0, 0.0, null), cleared.checkpoint)
    }
    @Test fun explicitPreviousUsesDisplayedPageAfterPartialClearFailureWithoutDiscardingPendingCheckpoint() {
        val saved = DiagnosticsViewportCheckpoint(2, 4, 12.0, null)
        val partialFailure = DiagnosticsViewport.project((0..149).map { "remaining-$it" }, saved, settled = false)
        assertEquals(1, partialFailure.page)
        assertEquals(saved, partialFailure.checkpoint)
        assertEquals(0, DiagnosticsViewport.move(partialFailure.checkpoint.page, 150, -1))
        assertEquals(1, DiagnosticsViewport.move(partialFailure.checkpoint.page, 150, 1))
        assertEquals(0, DiagnosticsViewport.move(2, 0, -1))
    }
}
