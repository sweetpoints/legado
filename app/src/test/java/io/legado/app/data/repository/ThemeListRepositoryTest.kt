package io.legado.app.data.repository

import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThemeListRepositoryTest {
    @Test
    fun stableContentIdentitySurvivesReorderAndDuplicateContentHasDistinctOccurrences() =
        runBlocking {
            val store = Fake()
            val repo = DefaultThemeListRepository(store)
            val before = repo.list()
            store.values = store.values.reversed()
            val after = repo.list()
            assertEquals(
                before.associate { it.json to it.key },
                after.associate { it.json to it.key },
            )
            store.values = store.values + store.values.first()
            val duplicate = repo.list()
            assertEquals(3, duplicate.map { it.key }.distinct().size)
            assertEquals(listOf(0, 0, 1), duplicate.map { it.occurrence })
        }

    @Test
    fun deleteUsesCapturedJsonAndOccurrenceAndNeverAnIndexFromReloadedList() = runBlocking {
        val store = Fake()
        val repo = DefaultThemeListRepository(store)
        val captured = repo.list().first()
        store.values = store.values.reversed()
        assertTrue(repo.delete(captured))
        assertEquals(listOf("two"), store.values.map { it.name })
        assertFalse(repo.delete(captured))
        assertEquals(listOf("two"), store.values.map { it.name })
    }

    @Test
    fun sharingKeepsEveryByteAndPreparationCapturesPayloadBeforeLaterListReplacement() =
        runBlocking {
            val store = Fake()
            val repo = DefaultThemeListRepository(store)
            val item = repo.list().first()
            repo.stageShare("session", "receipt", item)
            store.values = emptyList()
            assertEquals(item.json, repo.share("session", "receipt"))
            repo.apply(item)
            assertEquals(item.json, store.applied)
        }

    @Test
    fun importResultAndSourceTextAreForwardedExactly() = runBlocking {
        val store = Fake()
        val repo = DefaultThemeListRepository(store)
        assertTrue(repo.add("  json  "))
        assertEquals("  json  ", store.added)
        store.addSucceeds = false
        assertFalse(repo.add("bad"))
    }

    @Test
    fun allStorageAndApplyPreparationUseInjectedBackgroundDispatcher() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val store = Fake()
            val repo = DefaultThemeListRepository(store, io)
            val item = repo.list().first()
            repo.add("json")
            repo.apply(item)
            repo.stageShare("s", "r", item)
            repo.share("s", "r")
            repo.delete(item)
            assertEquals(6, store.threads.size)
            assertTrue(store.threads.all { it === store.threads.first() })
            assertNotSame(Thread.currentThread(), store.threads.first())
        }
    }

    private class Fake : ThemeListStore {
        var values =
            listOf(
                ThemeListValue("one", "{\"themeName\":\"one\",\"allFields\":1}"),
                ThemeListValue("two", "{\"themeName\":\"two\"}"),
            )
        var added: String? = null
        var applied: String? = null
        var addSucceeds = true
        val shares = mutableMapOf<String, String>()
        val threads = mutableListOf<Thread>()

        private fun record() {
            threads += Thread.currentThread()
        }

        override suspend fun list(): List<ThemeListValue> {
            record()
            return values.toList()
        }

        override suspend fun delete(json: String, occurrence: Int): Boolean {
            record()
            val index =
                values.withIndex().filter { it.value.json == json }.getOrNull(occurrence)?.index
                    ?: return false
            values = values.filterIndexed { position, _ -> position != index }
            return true
        }

        override suspend fun add(json: String): Boolean {
            record()
            added = json
            return addSucceeds
        }

        override suspend fun apply(json: String) {
            record()
            applied = json
        }

        override suspend fun writeShare(session: String, receipt: String, json: String) {
            record()
            shares[receipt] = json
        }

        override suspend fun readShare(session: String, receipt: String): String {
            record()
            return shares.getValue(receipt)
        }
    }
}
