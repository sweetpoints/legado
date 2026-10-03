package io.legado.app.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.help.CacheManager
import io.legado.app.utils.NetworkUtils
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class SourceLoginCookieRepositoryTest {
    private lateinit var database: AppDatabase
    private val source = "https://cookie-login-${UUID.randomUUID()}.invalid"

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
        CacheManager.deleteMemory("${NetworkUtils.getSubDomain(source)}_cookie")
        database.close()
    }

    @Test
    fun actualRoomWriteRunsOffMainAndPreservesCookieStoreDomainProtocol() = runBlocking {
        var capturedThread: Looper? = Looper.getMainLooper()
        val repository =
            AppSourceLoginCookieRepository(database) { _, _ -> capturedThread = Looper.myLooper() }
        withContext(Dispatchers.Main) { repository.store(source, "owned=exact; token=value") }
        assertNotSame(Looper.getMainLooper(), capturedThread)
        val row =
            withContext(Dispatchers.IO) {
                database.cookieDao.get(NetworkUtils.getSubDomain(source))!!
            }
        assertEquals("owned=exact; token=value", row.cookie)
        repository.store(source, null)
        assertEquals(
            "",
            withContext(Dispatchers.IO) {
                database.cookieDao.get(NetworkUtils.getSubDomain(source))!!.cookie
            },
        )
    }

    @Test
    fun failedWriteDoesNotPretendThatCookiePersistenceSucceeded() = runBlocking {
        val repository = AppSourceLoginCookieRepository(database) { _, _ -> error("Write failed") }
        try {
            repository.store(source, "owned=exact")
            fail("Must fail")
        } catch (_: IllegalStateException) {}
        assertNull(
            withContext(Dispatchers.IO) {
                database.cookieDao.get(NetworkUtils.getSubDomain(source))
            }
        )
    }
}
