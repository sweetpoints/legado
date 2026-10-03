package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookDetailStorageRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun before() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun after() {
        database.close()
    }

    private fun repo() = RoomBookDetailStorageRepository(database) { 1234L }

    private suspend fun insert(vararg books: Book) =
        withContext(Dispatchers.IO) { database.bookDao.insert(*books) }

    private suspend fun read(url: String) =
        withContext(Dispatchers.IO) { database.bookDao.getBook(url) }

    private suspend fun mutate(
        book: Book,
        kind: BookDetailMutationKind,
        inShelf: Boolean = true,
        text: String? = null,
        flag: Boolean = false,
        group: Long = 0,
        chapters: List<BookDetailChapter> = emptyList(),
        journal: (BookDetailWritePlan) -> Unit = {},
    ): BookDetailStorageResult =
        repo()
            .mutate(
                BookDetailBook.from(book),
                inShelf,
                chapters,
                BookDetailMutation(kind, text, flag, group),
                journal,
            )

    @Test
    fun generatedRuleCoverPatchesOnlyMissingSameSourceAndPreservesFreshReaderMetadata() =
        runBlocking {
            val stale = Book(bookUrl = "book", name = "Name", origin = "source", durChapterPos = 1)
            insert(stale)
            insert(stale.copy(durChapterIndex = 9, durChapterPos = 42, group = 7))
            val rule =
                BookDetailMutation(
                    BookDetailMutationKind.Cover,
                    text = "rule-cover",
                    onlyIfCoverMissing = true,
                )
            val first = repo().mutate(BookDetailBook.from(stale), true, emptyList(), rule) {}
            assertEquals("rule-cover", first.book.cover.path)
            assertEquals(42, read("book")!!.durChapterPos)
            assertEquals(7L, read("book")!!.group)
            insert(
                read("book")!!.copy(
                    customCoverUrl = "external",
                    persistedCoverUrl = "external-cache",
                    order = 0,
                )
            )
            val fresh = repo().mutate(BookDetailBook.from(stale), true, emptyList(), rule) {}
            assertEquals("external", fresh.book.cover.path)
            assertEquals("external-cache", read("book")!!.persistedCoverUrl)
            assertEquals(0, read("book")!!.order)
            insert(
                read("book")!!.copy(
                    name = "Renamed",
                    customCoverUrl = null,
                    persistedCoverUrl = null,
                )
            )
            repo().mutate(BookDetailBook.from(stale), true, emptyList(), rule) {}
            assertNull(read("book")!!.customCoverUrl)
            assertEquals("Renamed", read("book")!!.name)
            insert(read("book")!!.copy(name = stale.name, author = "New author"))
            repo().mutate(BookDetailBook.from(stale), true, emptyList(), rule) {}
            assertNull(read("book")!!.customCoverUrl)
            assertEquals("New author", read("book")!!.author)
            insert(
                read("book")!!.copy(
                    origin = "new-source",
                    author = stale.author,
                    customCoverUrl = null,
                    persistedCoverUrl = null,
                )
            )
            repo().mutate(BookDetailBook.from(stale), true, emptyList(), rule) {}
            assertNull(read("book")!!.customCoverUrl)
            assertEquals("new-source", read("book")!!.origin)
        }

    @Test
    fun groupPatchRetainsLatestProgressCoverReaderConfigAndUnrelatedBookFields() = runBlocking {
        val old =
            Book(
                bookUrl = "book",
                name = "Name",
                author = "Author",
                origin = "source",
                customCoverUrl = "old",
                persistedCoverUrl = "cached",
                variable = "old variable",
                group = 1,
            )
        insert(old)
        val fresh =
            old.copy(
                durChapterIndex = 9,
                durChapterPos = 42,
                customCoverUrl = "new external",
                persistedCoverUrl = "new cached",
                variable = "external variable",
                order = 77,
            )
        fresh.setSplitLongChapter(false)
        insert(fresh)
        val result = mutate(old, BookDetailMutationKind.Group, group = 9)
        val actual = read("book")!!
        assertEquals(9L, actual.group)
        assertEquals(9, actual.durChapterIndex)
        assertEquals(42, actual.durChapterPos)
        assertEquals("new external", actual.customCoverUrl)
        assertEquals("new cached", actual.persistedCoverUrl)
        assertEquals("external variable", actual.variable)
        assertEquals(77, actual.order)
        assertFalse(actual.getSplitLongChapter())
        assertEquals(actual.customCoverUrl, result.book.materializeBook().customCoverUrl)
    }

    @Test
    fun explicitCoverClearsPersistedCacheWhileCustomVariableChangesOnlyCustomKey() = runBlocking {
        val old =
            Book(
                bookUrl = "book",
                name = "Name",
                author = "Author",
                origin = "source",
                customCoverUrl = "old",
                persistedCoverUrl = "cached",
            )
        old.putVariable("other", "external value")
        insert(old)
        mutate(old, BookDetailMutationKind.Cover, text = "use_default_cover")
        assertNull(read("book")!!.persistedCoverUrl)
        assertEquals("use_default_cover", read("book")!!.customCoverUrl)
        mutate(old, BookDetailMutationKind.CustomVariable, text = "new custom")
        val actual = read("book")!!
        assertEquals("new custom", actual.getCustomVariable())
        assertEquals("external value", actual.getVariable("other"))
        assertEquals("use_default_cover", actual.customCoverUrl)
    }

    @Test
    fun disablingUpdatesClearsOnlyUpdateErrorAndTopUsesFreshMinimumAndTimestamp() = runBlocking {
        val book =
            Book(
                bookUrl = "book",
                name = "Name",
                type = BookType.text or BookType.updateError or BookType.archive,
                order = 5,
            )
        insert(book, Book(bookUrl = "other", name = "Other", order = -7))
        mutate(book, BookDetailMutationKind.CanUpdate, flag = false)
        assertEquals(BookType.text or BookType.archive, read("book")!!.type)
        assertFalse(read("book")!!.canUpdate)
        mutate(book, BookDetailMutationKind.Top)
        assertEquals(-8, read("book")!!.order)
        assertEquals(1234L, read("book")!!.durChapterTime)
    }

    @Test
    fun splitLongJournalCapturesUnmodifiedBaselineAndFailureDoesNotWriteRoom() = runBlocking {
        val book = Book(bookUrl = "book", name = "Name")
        book.setSplitLongChapter(true)
        insert(book)
        var plan: BookDetailWritePlan? = null
        assertTrue(
            runCatching {
                mutate(book, BookDetailMutationKind.SplitLong, flag = false) {
                    plan = it
                    error("disk failed")
                }
            }
                .isFailure
        )
        assertTrue(read("book")!!.getSplitLongChapter())
        val parsed = io.legado.app.utils.GSON.fromJson(plan!!.beforeJson, Book::class.java)
        assertTrue(parsed.getSplitLongChapter())
        val target = io.legado.app.utils.GSON.fromJson(plan!!.targetJson, Book::class.java)
        assertFalse(target.getSplitLongChapter())
        val restored = repo().recover(plan!!)
        assertFalse(restored.splitLongChapter)
        assertFalse(read("book")!!.getSplitLongChapter())
    }

    @Test
    fun searchPrivateEditsDoNotPersistUntilPositiveGroupPromotesBookAndChaptersAtomically() =
        runBlocking {
            val book = Book(bookUrl = "search", name = "Name", author = "Author", origin = "source")
            val chapter =
                BookChapter(bookUrl = "search", url = "chapter", index = 0, title = "Chapter")
            var journals = 0
            val preview =
                mutate(
                    book,
                    BookDetailMutationKind.Cover,
                    false,
                    text = "private cover",
                    journal = { journals++ },
                )
            assertEquals("private cover", preview.book.materializeBook().customCoverUrl)
            assertNull(read("search"))
            assertEquals(0, journals)
            val added =
                mutate(
                    preview.book.materializeBook(),
                    BookDetailMutationKind.Group,
                    false,
                    group = 3,
                    chapters = listOf(BookDetailChapter.from(chapter)),
                )
            assertTrue(added.inBookshelf)
            assertEquals(3L, read("search")!!.group)
            assertEquals("private cover", read("search")!!.customCoverUrl)
            assertEquals(
                "Chapter",
                withContext(Dispatchers.IO) {
                    database.bookChapterDao.getChapterList("search").single().title
                },
            )
        }

    @Test
    fun deletedShelfCannotBeRecreatedAndNewPreviewJournalCannotReplaceForeignSameNameDuringRecovery() =
        runBlocking {
            val owned = Book(bookUrl = "owned", name = "Name", author = "Author")
            insert(owned)
            withContext(Dispatchers.IO) { database.bookDao.delete(owned) }
            assertTrue(
                runCatching { mutate(owned, BookDetailMutationKind.Group, group = 2) }
                    .exceptionOrNull() is BookDetailMissing
            )
            assertNull(read("owned"))
            var plan: BookDetailWritePlan? = null
            assertTrue(
                runCatching {
                    mutate(
                        owned,
                        BookDetailMutationKind.JoinShelf,
                        false,
                        journal = {
                            plan = it
                            error("journal completed, before Room")
                        },
                    )
                }
                    .isFailure
            )
            val external = owned.copy(bookUrl = "foreign")
            insert(external)
            assertTrue(
                runCatching { repo().recover(plan!!) }.exceptionOrNull() is BookDetailConflict
            )
            assertEquals("foreign", read("foreign")!!.bookUrl)
            assertNull(read("owned"))
        }

    @Test
    fun alreadyCommittedRecoveryDoesNotOverwriteNewerReaderProgressOrChapterMetadata() =
        runBlocking {
            val book =
                Book(
                    bookUrl = "book",
                    name = "Name",
                    author = "Author",
                    origin = "source",
                    order = 5,
                )
            insert(book)
            val chapter =
                BookChapter(
                    bookUrl = "book",
                    url = "chapter",
                    index = 0,
                    title = "Original",
                    variable = "original",
                )
            withContext(Dispatchers.IO) { database.bookChapterDao.insert(chapter) }
            var plan: BookDetailWritePlan? = null
            mutate(
                book,
                BookDetailMutationKind.PrepareToc,
                chapters = listOf(BookDetailChapter.from(chapter)),
                journal = { plan = it },
            )
            assertNotNull(repo().recover(plan!!))
            withContext(Dispatchers.IO) {
                database.bookChapterDao.insert(chapter.copy(variable = "new chapter variable"))
            }
            assertTrue(
                runCatching { repo().recover(plan!!) }.exceptionOrNull() is BookDetailConflict
            )
            assertEquals(
                "new chapter variable",
                withContext(Dispatchers.IO) {
                    database.bookChapterDao.getChapterList("book").single().variable
                },
            )
            insert(read("book")!!.copy(durChapterPos = 999))
            assertTrue(
                runCatching { repo().recover(plan!!) }.exceptionOrNull() is BookDetailConflict
            )
            assertEquals(999, read("book")!!.durChapterPos)
        }

    @Test
    fun nativeReadingPositionOverridesOnlyExplicitCoordinatesAndFreshChapterMetadataIsNotReplaced() =
        runBlocking {
            val book = Book(bookUrl = "book", name = "Name", origin = "source", durChapterPos = 1)
            insert(book)
            val stale =
                BookChapter(
                    bookUrl = "book",
                    url = "chapter",
                    index = 0,
                    title = "Old",
                    variable = "old",
                )
            withContext(Dispatchers.IO) {
                database.bookChapterDao.insert(stale.copy(variable = "fresh"))
            }
            val result =
                repo().mutate(
                    BookDetailBook.from(book),
                    true,
                    listOf(BookDetailChapter.from(stale)),
                    BookDetailMutation(
                        BookDetailMutationKind.PrepareRead,
                        position = BookDetailPosition(4, 17, 2, 1),
                    ),
                ) {}
            val actual = read("book")!!
            assertEquals(4, actual.durChapterIndex)
            assertEquals(17, actual.durChapterPos)
            assertEquals(2, actual.durVolumeIndex)
            assertEquals(1, actual.chapterInVolumeIndex)
            assertTrue(result.inBookshelf)
            assertEquals(
                "fresh",
                withContext(Dispatchers.IO) {
                    database.bookChapterDao.getChapterList("book").single().variable
                },
            )
        }
}
