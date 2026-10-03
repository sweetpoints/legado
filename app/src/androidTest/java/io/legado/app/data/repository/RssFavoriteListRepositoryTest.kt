package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssStar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class RssFavoriteListRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomRssFavoriteListRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomRssFavoriteListRepository(database)
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun insert(vararg stars: RssStar) =
        withContext(Dispatchers.IO) { database.rssStarDao.insert(*stars) }

    private fun star(origin: String, link: String, group: String, time: Long) =
        RssStar(
            origin = origin,
            link = link,
            group = group,
            starTime = time,
            title = "Title",
            content = "Full content",
            variable = "{v}",
            type = 2,
            durPos = 19,
        )

    @Test
    fun actualRoomGroupsAndRowsKeepGroupAscendingAndNewestFirstWithoutMutatingProjection() =
        runBlocking {
            val a = star("origin", "a", "B", 4)
            val b = star("origin", "b", "A", 2)
            val c = star("origin", "c", "", 3)
            insert(a, b, c)
            val snapshot = repository.observe().first()
            assertEquals(listOf("", "A", "B"), snapshot.groups)
            assertEquals(listOf(4L, 3L, 2L), snapshot.rows.map { it.starTime })
            a.title = "Outside mutation"
            assertEquals("Title", snapshot.rows.first().title)
            insert(b.copy(group = "C", starTime = 8))
            val updated = repository.observe().first()
            assertEquals(listOf("", "B", "C"), updated.groups)
            assertEquals(8L, updated.rows.first().starTime)
        }

    @Test
    fun compositeIdsIsolateSameLinkAndResolveLatestArticleWithCompleteReadMetadata() = runBlocking {
        val a = star("ab", "c", "A", 1)
        val b = star("a", "bc", "A", 2)
        val c = star("other", "c", "A", 3)
        insert(a, b, c)
        val key = RoomRssFavoriteListRepository.key(a.origin, a.link)
        assertEquals(3, repository.observe().first().rows.map { it.id }.distinct().size)
        val latest =
            a.copy(title = "Updated", content = "Latest body", group = "B", type = 1, durPos = 33)
        insert(latest)
        assertEquals(latest, repository.resolve(key))
        assertEquals(b, repository.resolve(RoomRssFavoriteListRepository.key(b.origin, b.link)))
        repository.delete(key)
        assertNull(repository.resolve(key))
        assertEquals(2, repository.observe().first().rows.size)
        repository.delete(key)
        assertEquals(2, repository.observe().first().rows.size)
    }

    @Test
    fun exactEmptyGroupDeletionAndAllDeletionHaveRealRoomScope() = runBlocking {
        insert(star("o", "1", "", 1), star("o", "2", "默认分组", 2), star("o", "3", "A", 3))
        repository.deleteGroup("")
        assertEquals(listOf("A", "默认分组"), repository.observe().first().groups)
        repository.deleteGroup("missing")
        assertEquals(2, repository.observe().first().rows.size)
        repository.deleteAll()
        assertTrue(repository.observe().first().rows.isEmpty())
        assertTrue(repository.observe().first().groups.isEmpty())
    }
}
