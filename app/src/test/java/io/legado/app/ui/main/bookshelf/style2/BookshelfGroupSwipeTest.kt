package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.widget.recycler.horizontalSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfGroupSwipeTest {

    private val groups =
        listOf(
            BookGroup(groupId = 1, groupName = "First"),
            BookGroup(groupId = 2, groupName = "Second"),
            BookGroup(groupId = 4, groupName = "Third"),
        )

    @Test
    fun `swiping left selects the next visible group`() {
        assertEquals(4L, adjacentBookshelfGroupId(groups, 2, 1))
    }

    @Test
    fun `swiping right selects the previous visible group`() {
        assertEquals(1L, adjacentBookshelfGroupId(groups, 2, -1))
    }

    @Test
    fun `root missing and edge groups leave the gesture to the parent pager`() {
        assertNull(adjacentBookshelfGroupId(groups, BookGroup.IdRoot, 1))
        assertNull(adjacentBookshelfGroupId(groups, 1, -1))
        assertNull(adjacentBookshelfGroupId(groups, 4, 1))
    }

    @Test
    fun `horizontal swipe direction respects paging slop and vertical movement`() {
        assertEquals(1, horizontalSwipeDirection(100, 100, 70, 105, 20))
        assertEquals(-1, horizontalSwipeDirection(100, 100, 130, 95, 20))
        assertEquals(0, horizontalSwipeDirection(100, 100, 85, 100, 20))
        assertEquals(0, horizontalSwipeDirection(100, 100, 70, 140, 20))
    }

    @Test
    fun gestureCommitsOnlyAfterReleaseAndCancellationOrReversalNeverChangesGroup() {
        val gesture = BookshelfFolderGesture(50f, previous = true, next = true)
        assertFalse(gesture.move(-20f, 0f))
        assertTrue(gesture.move(-70f, 5f))
        assertNull(gesture.finish(-70f, 5f, cancelled = true))
        assertTrue(gesture.move(-70f, 5f))
        assertNull(gesture.finish(80f, 5f, cancelled = false))
        assertTrue(gesture.move(-70f, 5f))
        assertEquals(1, gesture.finish(-100f, 5f, cancelled = false))
        assertNull(gesture.finish(-100f, 5f, cancelled = false))
    }

    @Test
    fun verticalMotionRootAndGroupEdgesStayUncaptured() {
        val root = BookshelfFolderGesture(50f, false, false)
        assertFalse(root.move(-100f, 0f))
        assertNull(root.finish(-100f, 0f, false))
        val first = BookshelfFolderGesture(50f, false, true)
        assertFalse(first.move(100f, 0f))
        assertFalse(first.move(-70f, 90f))
        assertTrue(first.move(-100f, 0f))
        assertEquals(1, first.finish(-100f, 0f, false))
        val last = BookshelfFolderGesture(50f, true, false)
        assertFalse(last.move(-100f, 0f))
        assertTrue(last.move(100f, 0f))
        assertEquals(-1, last.finish(100f, 0f, false))
    }
}
