package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssArticlesSessionRepositoryTest {
    private val directory =
        File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "rss-articles-fixture-${UUID.randomUUID()}",
        )
    private val session = UUID.randomUUID().toString()

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun fullLargeParametersAndPageUrlsRestoreWithoutAndroidBundle() = runBlocking {
        val value =
            RssArticlesSession(
                RssArticlesParameters(
                    "S".repeat(500000),
                    "Name",
                    "U".repeat(500000),
                    "Q".repeat(500000),
                ),
                initialized = true,
                page = 8,
                nextUrl = "N".repeat(500000),
                retry = "NextPage",
                order = 10,
                revision = 11,
                hasMore = false,
            )
        FileRssArticlesSessionRepository(directory).write(session, value)
        assertEquals(value, FileRssArticlesSessionRepository(directory).read(session))
    }

    @Test
    fun staleCheckpointCannotOverwriteLatestPageProgress() = runBlocking {
        val store = FileRssArticlesSessionRepository(directory)
        val value =
            RssArticlesSession(
                RssArticlesParameters("source", "Name", "url"),
                page = 9,
                revision = 30,
            )
        store.write(session, value)
        store.write(session, value.copy(page = 2, revision = 1))
        assertEquals(9, store.read(session)!!.page)
    }

    @Test
    fun releasedOwnerRejectsLatePageSessionWrites() = runBlocking {
        val store = FileRssArticlesSessionRepository(directory)
        val value = RssArticlesSession(RssArticlesParameters("source", "Name", "url"), revision = 1)
        store.write(session, value)
        store.release(session)
        store.write(session, value.copy(revision = 99))
        assertNull(store.read(session))
        assertFalse(File(directory, "$session.json").exists())
    }

    @Test
    fun failedInitialWriteCanRetrySameSessionAfterDirectoryRepair() = runBlocking {
        directory.writeText("block")
        val store = FileRssArticlesSessionRepository(directory)
        val value = RssArticlesSession(RssArticlesParameters("source", "Name", "url"), revision = 1)
        assertTrue(runCatching { store.write(session, value) }.isFailure)
        assertTrue(directory.delete())
        store.write(session, value)
        assertEquals(value, store.read(session))
    }
}
