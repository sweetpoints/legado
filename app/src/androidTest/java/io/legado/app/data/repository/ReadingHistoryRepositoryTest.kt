package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReadingHistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private var prunes = 0

    @Before
    fun before() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        prunes = 0
    }

    @After
    fun after() {
        database.close()
    }

    private fun repo() = RoomReadingHistoryRepository(context, database) { prunes++ }

    @Test
    fun filteringKeepsGlobalSummaryAndLatestDeviceSnapshotWithoutAnyHistoryOrShelfWrites() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.readRecordDao.insert(
                    ReadRecord(
                        "one",
                        "Alpha",
                        "Author",
                        100,
                        10,
                        lastChapterTitle = "old",
                        coverUrl = "old-cover",
                    ),
                    ReadRecord(
                        "two",
                        "Alpha",
                        "Author",
                        200,
                        20,
                        lastChapterTitle = "new",
                        coverUrl = "new-cover",
                    ),
                    ReadRecord("one", "Beta", "Other", 50, 5),
                )
            }
            val before = withContext(Dispatchers.IO) { database.readRecordDao.all }
            val filtered = repo().load("Author", 1)
            assertEquals(2, filtered.count)
            assertEquals(350L, filtered.total)
            assertEquals(1, filtered.rows.size)
            assertEquals(300L, filtered.rows.single().readTime)
            assertEquals("new", filtered.rows.single().chapter)
            assertEquals("new-cover", filtered.rows.single().cover.snapshot)
            assertEquals(listOf("Alpha", "Beta"), filtered.top.map { it.identity.name })
            withContext(Dispatchers.IO) {
                assertEquals(before, database.readRecordDao.all)
                assertTrue(database.bookDao.all.isEmpty())
            }
        }

    @Test
    fun allSortModesAndSameNamedAuthorIdentitiesRemainSeparateWithFreshBookshelfMetadata() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.readRecordDao.insert(
                    ReadRecord("device", "Same", "A", 100, 10, lastChapterTitle = "history"),
                    ReadRecord("device", "Same", "B", 200, 20),
                    ReadRecord("device", "Alpha", "Z", 50, 30),
                )
                database.bookDao.insert(
                    Book(
                        bookUrl = "old",
                        name = "Same",
                        author = "A",
                        durChapterTime = 1,
                        durChapterTitle = "old shelf",
                        coverUrl = "old",
                    ),
                    Book(
                        bookUrl = "new",
                        name = "Same",
                        author = "A",
                        durChapterTime = 2,
                        durChapterTitle = "new shelf",
                        coverUrl = "new",
                    ),
                )
            }
            val repository = repo()
            assertEquals(
                listOf("Alpha", "Same", "Same"),
                repository.load("", 0).rows.map { it.identity.name },
            )
            assertEquals(
                listOf("B", "A", "Z"),
                repository.load("", 1).rows.map { it.identity.author },
            )
            assertEquals(
                listOf("Z", "B", "A"),
                repository.load("", 2).rows.map { it.identity.author },
            )
            val old = repository.load("", 1).rows.single { it.identity.author == "A" }
            assertEquals("new shelf", old.chapter)
            assertEquals("new", old.cover.current)
            withContext(Dispatchers.IO) {
                database.bookDao.insert(
                    Book(
                        bookUrl = "new",
                        name = "Same",
                        author = "A",
                        durChapterTime = 2,
                        durChapterTitle = "changed",
                    )
                )
            }
            assertEquals("new shelf", old.chapter)
            assertEquals(
                "changed",
                repository.load("", 1).rows.single { it.identity.author == "A" }.chapter,
            )
            assertEquals(3, repository.load("", 1).rows.map { it.key }.distinct().size)
        }

    @Test
    fun exactIdentityDeletionAlsoDeletesResolvedUnknownButNeverAnotherAuthorAndPrunesAfterCommit() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.readRecordDao.insert(
                    ReadRecord("one", "Same", "A", 10),
                    ReadRecord("two", "Same", "", 20, resolvedAuthor = "A"),
                    ReadRecord("one", "Same", "B", 30),
                )
            }
            repo().delete(ReadingHistoryIdentity("Same", "A"))
            val result = repo().load("", 0)
            assertEquals(listOf("B"), result.rows.map { it.identity.author })
            assertEquals(30L, result.total)
            assertEquals(1, prunes)
            repo().clear()
            assertEquals(0, repo().load("", 0).count)
            assertEquals(2, prunes)
        }

    @Test
    fun removingOneLegacyLabelKeepsUndividedDurationAndLatestSnapshotWhenIdentityMerges() =
        runBlocking {
            val combined = ReadRecordAuthors.merge("A", "B")
            withContext(Dispatchers.IO) {
                database.readRecordDao.insert(
                    ReadRecord("one", "Same", combined, 100, 30, lastChapterTitle = "latest"),
                    ReadRecord("one", "Same", "B", 50, 20, lastChapterTitle = "older"),
                )
            }
            val old = repo().load("", 0).rows.single { it.combined }
            assertEquals(listOf("A", "B"), old.legacyAuthors)
            assertEquals("A、B", old.displayAuthor)
            repo().removeAuthor(old.identity, "A")
            val result = repo().load("", 0)
            assertEquals(1, result.count)
            assertEquals(150L, result.total)
            assertEquals("B", result.rows.single().identity.author)
            assertEquals("latest", result.rows.single().chapter)
            assertEquals(1, prunes)
        }

    @Test
    fun readerResolutionUsesExactAuthorNewestBookAndSearchesIfThatIdentityIsAbsent() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookDao.insert(
                Book(bookUrl = "old", name = "Same", author = "A", durChapterTime = 1),
                Book(bookUrl = "new", name = "Same", author = "A", durChapterTime = 2),
                Book(bookUrl = "other", name = "Same", author = "B", type = BookType.audio),
            )
        }
        val repository = repo()
        assertEquals(
            ReadingHistoryDestination(ReadingHistoryReader.Text, "new", "Same"),
            repository.destination(ReadingHistoryIdentity("Same", "A")),
        )
        assertEquals(
            ReadingHistoryReader.Audio,
            repository.destination(ReadingHistoryIdentity("Same", "B")).kind,
        )
        assertEquals(
            ReadingHistoryDestination(ReadingHistoryReader.Search, "", "Same"),
            repository.destination(ReadingHistoryIdentity("Same", "missing")),
        )
    }

    @Test
    fun deltaPreferencesDoNotRevertAnotherHostsDaysOrSecondsSetting() = runBlocking {
        val repository = repo()
        val original = repository.preferences()
        try {
            AppConfig.readRecordUseDays = !original.days
            AppConfig.readRecordShowSeconds = !original.seconds
            repository.preferences(
                original.copy(simple = !original.simple),
                setOf(ReadingHistoryPreference.Simple),
            )
            assertEquals(!original.days, repository.preferences().days)
            assertEquals(!original.seconds, repository.preferences().seconds)
            assertEquals(!original.simple, repository.preferences().simple)
        } finally {
            repository.preferences(original, ReadingHistoryPreference.entries.toSet())
        }
    }
}
