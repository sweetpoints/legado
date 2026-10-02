package io.legado.app.ui.main.bookshelf

import io.legado.app.ui.main.bookshelf.components.BookshelfBookCardModel
import io.legado.app.ui.main.bookshelf.components.BookshelfGroupCardModel
import org.junit.Assert.*
import org.junit.Test

class BookshelfItemAnimatorTest {
    @Test fun composeBookSnapshotChangesMetadataWithoutChangingItsStableScrollKey() {
        val before = BookshelfBookCardModel("url", "Name", "Author", "Old", "Latest")
        val after = before.copy(currentChapter = "New", updating = true, unreadCount = 3)
        assertEquals(before.key, after.key); assertNotEquals(before, after)
    }
    @Test fun groupRenameKeepsIdentityAndEqualBookMetadataDoesNotMergeDifferentUrls() {
        val group = BookshelfGroupCardModel(8, "Group"); assertEquals(group.key, group.copy(name = "Renamed").key)
        val first = BookshelfBookCardModel("first", "Name", "Author", "Read", "Latest")
        assertNotEquals(first.key, first.copy(key = "second").key)
    }
}
