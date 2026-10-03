package io.legado.app.ui.about

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AppLogRow
import io.legado.app.data.repository.DefaultAppLogsRepository
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogDialogLiveUpdateTest {
    @get:Rule val folder = TemporaryFolder()

    @Before
    fun setUp() {
        AppLog.clear()
        HttpLogStore.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
        HttpLogStore.clear()
    }

    @Test
    fun writesAndClearUpdateAnAlreadySubscribedRepository() = runTest {
        val repository = DefaultAppLogsRepository(folder.root)
        val updates = Channel<List<AppLogRow>>(Channel.UNLIMITED)
        backgroundScope.launch { repository.logs.collect { updates.send(it) } }
        assertTrue(updates.receive().isEmpty())
        AppLog.putNotSave("new message")
        assertEquals("new message", updates.receive().single().message)
        AppLog.clear()
        assertTrue(updates.receive().isEmpty())
    }

    @Test
    fun clearRemovesApplicationLogsAndHttpRecords() = runTest {
        val record = record()
        HttpLogStore.add(record)
        val repository = DefaultAppLogsRepository(folder.root)
        assertEquals(record.summary, repository.logs.first().single().message)
        assertNotNull(HttpLogStore.get(record.id))
        repository.clearLogs()
        assertTrue(repository.logs.first().isEmpty())
        assertNull(HttpLogStore.get(record.id))
    }

    private fun record() =
        HttpLogRecord(
            101,
            0,
            "GET",
            "/test",
            "https://example.com/test",
            200,
            5,
            "",
            "",
            "",
            "test body",
            null,
        )
}
