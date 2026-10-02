package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun setUp() { AppLog.clear(); HttpLogStore.clear() }
    @After fun tearDown() { AppLog.clear(); HttpLogStore.clear() }

    @Test fun emptyLogDoesNotProduceASharePayload() = runTest {
        assertNull(repository().prepareExport())
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun exportAtTheTextLimitUsesTextAndIncludesTheEntireSnapshot() = runTest {
        val text = setExportLength(DefaultAppLogsRepository.MAX_SHARE_TEXT)
        val export = repository().prepareExport() as AppLogExport.Text
        assertEquals(text, export.text)
        assertEquals(64_000, export.text.length)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun exportAboveTheLimitUsesAFileAndNeverOverwritesAnEarlierShare() = runTest {
        val firstText = setExportLength(DefaultAppLogsRepository.MAX_SHARE_TEXT + 1)
        val first = repository().prepareExport() as AppLogExport.Document
        assertEquals(firstText, first.file.readText())
        AppLog.clear()
        AppLog.putNotSave("second " + "y".repeat(64_000))
        val secondText = AppLog.exportText(AppLog.logs)
        val second = repository().prepareExport() as AppLogExport.Document
        assertNotEquals(first.file, second.file)
        assertEquals(secondText, second.file.readText())
        assertEquals(firstText, first.file.readText())
    }

    @Test fun httpDetailsFallbackToTheSummaryAfterRecordEviction() = runTest {
        val record = HttpLogRecord(102, 0, "POST", "/test", "https://example.com/test",
            200, 10, "", "payload", "", "response", null)
        HttpLogStore.add(record)
        val repository = repository()
        val row = repository.logs.first().single()
        assertTrue(row.hasDetails)
        assertEquals(AppLogDetail(row.id, "HTTP", record.detail), repository.readDetail(row.id))
        HttpLogStore.clear()
        assertEquals(AppLogDetail(row.id, "HTTP", record.summary), repository.readDetail(row.id))
    }

    @Test fun throwableDetailsKeepTheStackTraceAndPlainLogsHaveNoDetailAction() = runTest {
        val throwable = IllegalArgumentException("bad input")
        AppLog.putNotSave("failure", throwable)
        AppLog.putNotSave("plain message")
        val repository = repository()
        val rows = repository.logs.first()
        assertFalse(rows[0].hasDetails)
        assertTrue(rows[1].hasDetails)
        assertNull(repository.readDetail(rows[0].id))
        assertEquals(throwable.stackTraceToString(), repository.readDetail(rows[1].id)?.text)
        assertNull(repository.readDetail(Long.MAX_VALUE))
    }

    @Test fun exportedErrorsIncludeTheStackTraceInChronologicalOrder() = runTest {
        val throwable = IllegalStateException("sample failure")
        AppLog.putNotSave("first message", throwable)
        AppLog.putNotSave("second message")
        val text = (repository().prepareExport() as AppLogExport.Text).text
        assertTrue(text.indexOf("first message") < text.indexOf("second message"))
        assertTrue(text.contains(throwable.stackTraceToString().trimEnd().prependIndent("    ")))
    }

    @Test fun fileCreationFailureDoesNotSilentlyDropTheShare() = runTest {
        setExportLength(64_001)
        val notADirectory = folder.newFile("file")
        try {
            DefaultAppLogsRepository(notADirectory).prepareExport()
            fail("Expected file creation failure")
        } catch (_: java.io.IOException) {
            assertTrue(notADirectory.isFile)
        }
    }

    private fun repository() = DefaultAppLogsRepository(folder.root)

    private fun setExportLength(length: Int): String {
        AppLog.clear()
        AppLog.putNotSave("")
        val prefixLength = AppLog.exportText(AppLog.logs).length
        AppLog.clear()
        AppLog.putNotSave("x".repeat(length - prefixLength))
        return AppLog.exportText(AppLog.logs).also { assertEquals(length, it.length) }
    }
}
