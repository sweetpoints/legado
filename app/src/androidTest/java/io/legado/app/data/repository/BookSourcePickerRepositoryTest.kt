package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookSourcePickerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomBookSourcePickerRepository

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomBookSourcePickerRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun enabledFlowSearchNameGroupUrlCommentAndCustomOrderRemainLive() = runBlocking {
        val alpha =
            BookSource(
                bookSourceUrl = "https://alpha.invalid",
                bookSourceName = "Alpha",
                bookSourceGroup = "Group",
                bookSourceComment = "Special comment",
                customOrder = 9,
            )
        val beta =
            BookSource(
                bookSourceUrl = "https://beta.invalid",
                bookSourceName = "Beta",
                customOrder = 1,
            )
        val disabled =
            BookSource(
                bookSourceUrl = "https://disabled.invalid",
                bookSourceName = "Alpha disabled",
                enabled = false,
            )
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(alpha, beta, disabled) }
        assertEquals(
            listOf(beta.bookSourceUrl, alpha.bookSourceUrl),
            repository.observe("").first().map { it.url },
        )
        for (query in listOf("Alpha", "Group", "alpha.invalid", "Special", "ALPHA")) {
            assertEquals(
                listOf(alpha.bookSourceUrl),
                repository.observe(query).first().map { it.url },
            )
        }
        assertTrue(repository.observe("missing").first().isEmpty())
        val updates = Channel<List<BookSourcePickerItem>>(Channel.UNLIMITED)
        val job = launch { repository.observe("").collect { updates.send(it) } }
        try {
            withTimeout(10000) { updates.receive() }
            withContext(Dispatchers.IO) {
                database.bookSourceDao.insert(
                    alpha.copy(bookSourceName = "Renamed", customOrder = -1),
                    beta.copy(enabled = false),
                )
            }
            val current =
                withTimeout(10000) {
                    var next = updates.receive()
                    while (next.size != 1) next = updates.receive()
                    next
                }
            assertEquals("Renamed (Group)", current.single().displayName)
        } finally {
            job.cancelAndJoin()
            updates.close()
        }
    }

    @Test
    fun selectionReadsLatestFullEntityRatherThanProjection() = runBlocking {
        val source =
            BookSource(
                bookSourceUrl = "https://full.invalid",
                bookSourceName = "Full",
                mainJs = "large script".repeat(1000),
                header = "headers",
                loginCheckJs = "login",
                jsLib = "library",
                coverDecodeJs = "cover",
                variableComment = "variables",
                enabledExplore = false,
            )
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(source) }
        val row = repository.observe("").first().single()
        val updated =
            source.copy(bookSourceName = "Latest", enabled = false, bookSourceComment = "Fresh")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(updated) }
        assertEquals(GSON.toJson(updated), repository.source(row.url))
        assertNull(repository.source("https://missing.invalid"))
    }

    @Test
    fun preferencesUseIoAndRejectOutOfRangeWithoutWriting() = runBlocking {
        val writes = mutableListOf<Int>()
        val tested =
            RoomBookSourcePickerRepository(
                database,
                {
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    27
                },
                { value ->
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    writes += value
                },
            )
        assertEquals(27, tested.delay())
        tested.saveDelay(0)
        tested.saveDelay(9999)
        assertTrue(runCatching { tested.saveDelay(-1) }.isFailure)
        assertTrue(runCatching { tested.saveDelay(10000) }.isFailure)
        assertEquals(listOf(0, 9999), writes)
    }

    @Test
    fun actualAppConfigDelayRoundTripsAcrossRepositoryInstances() = runBlocking {
        val previous = withContext(Dispatchers.IO) { AppConfig.batchChangeSourceDelay }
        try {
            repository.saveDelay(873)
            assertEquals(873, RoomBookSourcePickerRepository(database).delay())
        } finally {
            withContext(Dispatchers.IO) { AppConfig.batchChangeSourceDelay = previous }
        }
    }
}
