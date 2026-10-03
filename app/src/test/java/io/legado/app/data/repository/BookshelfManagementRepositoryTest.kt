package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfManagementRepositoryTest {
    // Book equality is URL-only. Room emits metadata changes even when entity IDs stay the same.
    private class Rows(initial: List<Book>) {
        private val changes = MutableStateFlow(0L)
        var value: List<Book> = initial
            set(next) {
                field = next
                changes.value++
            }

        fun flow(): Flow<List<Book>> = changes.map { value }
    }

    private class Store : BookshelfManagementStore {
        val rows =
            Rows(
                listOf(
                    Book(bookUrl = "a", name = "Alpha", author = "one", group = 1, order = 1),
                    Book(bookUrl = "hidden", name = "Hidden", author = "two", group = 2, order = 2),
                    Book(bookUrl = "b", name = "Beta", author = "three", group = 3, order = 3),
                )
            )
        val names = MutableStateFlow(listOf(BookGroup(1, "First"), BookGroup(2, "Second")))
        var sorting = 3
        var title = false
        var inTransaction = false
        val writes = mutableListOf<String>()

        override fun books(groupId: Long) = rows.flow()

        override fun groups() = names

        override fun sort(groupId: Long) = sorting

        override fun openTitle() = title

        override fun setOpenTitle(value: Boolean) {
            title = value
        }

        override fun read(id: String): Book? {
            check(inTransaction)
            return rows.value.firstOrNull { it.bookUrl == id }
        }

        override fun allByOrder(): List<Book> {
            check(inTransaction)
            return rows.value.sortedBy { it.order }
        }

        override fun updateMetadata(book: Book) {
            check(inTransaction)
            writes += book.bookUrl
            rows.value = rows.value.map { if (it.bookUrl == book.bookUrl) book else it }
        }

        override fun updateOrder(id: String, order: Int) {
            check(inTransaction)
            writes += "order:$id"
            rows.value = rows.value.map { if (it.bookUrl == id) it.copy(order = order) else it }
        }

        override fun transaction(block: () -> Unit) {
            check(!inTransaction)
            inTransaction = true
            try {
                block()
            } finally {
                inTransaction = false
            }
        }
    }

    @Test
    fun projectionRetainsAllSearchFieldsAndIndependentImmutableGroupNames() = runTest {
        val store = Store()
        store.rows.value =
            store.rows.value.map { if (it.bookUrl == "b") it.copy(intro = "find-by-intro") else it }
        val repo =
            DefaultBookshelfManagementRepository(store, StandardTestDispatcher(testScheduler))
        val snapshot = repo.observe(1, "find-by-intro").first()
        assertEquals(listOf("b"), snapshot.books.map { it.id })
        assertEquals("First,Second", snapshot.books.single().groupNames)
        store.names.value.first().groupName = "changed after projection"
        assertEquals("First", snapshot.groups.first().name)
        assertEquals("First,Second", snapshot.books.single().groupNames)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun groupEditsReReadLatestMetadataAndIgnoreMissingOrDuplicateIds() = runTest {
        val store = Store()
        val current =
            store.rows.value
                .first()
                .copy(
                    name = "latest",
                    customCoverUrl = "new-cover",
                    persistedCoverUrl = "saved-cover",
                    durChapterIndex = 9,
                )
        store.rows.value = listOf(current) + store.rows.value.drop(1)
        val repo =
            DefaultBookshelfManagementRepository(store, StandardTestDispatcher(testScheduler))
        repo.group(listOf("a", "missing", "a"), 2, ShelfGroupMutation.Add)
        val book = store.rows.value.first()
        assertEquals(3L, book.group)
        assertEquals("latest", book.name)
        assertEquals("new-cover", book.customCoverUrl)
        assertEquals("saved-cover", book.persistedCoverUrl)
        assertEquals(9, book.durChapterIndex)
        assertEquals(listOf("a"), store.writes)
        repo.group(listOf("a"), 1, ShelfGroupMutation.Remove)
        assertEquals(2L, store.rows.value.first().group)
        repo.group(listOf("a"), 8, ShelfGroupMutation.Replace)
        assertEquals(8L, store.rows.value.first().group)
    }

    @Test
    fun disableUpdatesClearsOnlyUpdateErrorAndKeepsFreshFields() = runTest {
        val store = Store()
        store.rows.value =
            store.rows.value.map {
                if (it.bookUrl == "a")
                    it.copy(
                        type = BookType.text or BookType.updateError,
                        latestChapterTitle = "new chapter",
                    )
                else it
            }
        val repo =
            DefaultBookshelfManagementRepository(store, StandardTestDispatcher(testScheduler))
        repo.canUpdate(listOf("a"), false)
        val book = store.rows.value.first()
        assertFalse(book.canUpdate)
        assertEquals(0, book.type and BookType.updateError)
        assertTrue(book.type and BookType.text != 0)
        assertEquals("new chapter", book.latestChapterTitle)
        repo.canUpdate(listOf("a"), true)
        assertTrue(store.rows.value.first().canUpdate)
    }

    @Test
    fun equalOrderResetKeepsHiddenSlotsAndNeverOverwritesMetadata() = runTest {
        val store = Store()
        val repo =
            DefaultBookshelfManagementRepository(store, StandardTestDispatcher(testScheduler))
        repo.order(
            listOf(
                ShelfOrderAssignment("b", 99),
                ShelfOrderAssignment("missing", 1),
                ShelfOrderAssignment("a", 99),
                ShelfOrderAssignment("b", 4),
            ),
            true,
        )
        assertEquals(
            listOf("b", "hidden", "a"),
            store.rows.value.sortedBy { it.order }.map { it.bookUrl },
        )
        assertEquals("Alpha", store.rows.value.first { it.bookUrl == "a" }.name)
        assertTrue(store.writes.all { it.startsWith("order:") })
    }

    @Test
    fun unequalOrderDragWritesOnlyCurrentIdsAndTitlePreferenceStaysExplicit() = runTest {
        val store = Store()
        val repo =
            DefaultBookshelfManagementRepository(store, StandardTestDispatcher(testScheduler))
        repo.order(
            listOf(
                ShelfOrderAssignment("a", 3),
                ShelfOrderAssignment("b", 1),
                ShelfOrderAssignment("gone", 2),
            ),
            false,
        )
        assertEquals(
            listOf("b", "hidden", "a"),
            store.rows.value.sortedBy { it.order }.map { it.bookUrl },
        )
        assertFalse(store.title)
        repo.openTitle(true)
        assertTrue(store.title)
    }
}
