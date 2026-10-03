package io.legado.app.constant

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppLogStateTest {
    @Before
    fun setUp() {
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
    }

    @Test
    fun bothWritePathsPublishNewestFirstAndKeepLegacySnapshots() {
        val error = IllegalStateException("test failure")
        AppLog.put("saved")
        AppLog.putNotSave("memory", error)
        val entries = AppLog.entries.value
        assertEquals(listOf("memory", "saved"), entries.map { it.message })
        assertSame(error, entries.first().throwable)
        assertEquals(entries.map { Triple(it.time, it.message, it.throwable) }, AppLog.logs)
    }

    @Test
    fun existingIdsSurvivePrependingAndAreNotReusedAfterClear() {
        AppLog.putNotSave("same message")
        val first = AppLog.entries.value.single()
        AppLog.putNotSave("same message")
        val second = AppLog.entries.value.first()
        assertEquals(first.id, AppLog.entries.value.last().id)
        assertNotEquals(first.id, second.id)
        AppLog.clear()
        assertTrue(AppLog.entries.value.isEmpty())
        assertTrue(AppLog.logs.isEmpty())
        AppLog.putNotSave("new log")
        assertTrue(AppLog.entries.value.single().id > second.id)
    }

    @Test
    fun boundedStorageDoesNotMutatePreviouslyPublishedLists() {
        AppLog.putNotSave("first")
        val previousSnapshot = AppLog.entries.value
        repeat(120) { AppLog.putNotSave("entry $it") }
        val entries = AppLog.entries.value
        assertEquals(100, entries.size)
        assertEquals("entry 119", entries.first().message)
        assertEquals("entry 20", entries.last().message)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertEquals(listOf("first"), previousSnapshot.map { it.message })
    }

    @Test
    fun nullMessagesDoNotChangeTheCurrentSnapshot() {
        AppLog.putNotSave("retained")
        val before = AppLog.entries.value
        AppLog.put(null)
        AppLog.putNotSave(null)
        assertSame(before, AppLog.entries.value)
    }
}
