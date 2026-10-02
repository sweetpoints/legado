package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class HttpTtsImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualRoomComparisonStableEditingAndSelectedBatchPreserveEveryEngineField() = runBlocking {
        val id = System.nanoTime(); val session = UUID.randomUUID().toString()
        val new = HttpTTS(id, "New", "url", pauseDuration = 456, enabledCookieJar = true, jsLib = "function sign() {}", lastUpdateTime = 4)
        val old = HttpTTS(id + 1, "Existing", "old", lastUpdateTime = 3)
        val incoming = old.copy(name = "Update", url = "new", lastUpdateTime = 4)
        val repo = AppHttpTtsImportRepository(context)
        try {
            withContext(Dispatchers.IO) { appDb.httpTTSDao.insert(old) }
            val items = repo.read(GSON.toJson(listOf(new, incoming)))
            assertEquals(2, items.size); assertNull(items[0].localUpdatedAt); assertEquals(3L, items[1].localUpdatedAt)
            assertTrue(items.all { it.selectedByDefault })
            val edited = repo.edit(items[1].key, GSON.toJson(incoming.copy(header = "{\"key\":\"value\"}")))
            assertEquals(items[1].key, edited.key)
            repo.stage(session, listOf(items[0], edited))
            assertEquals(HttpTtsImportSession(listOf(items[0], edited)), repo.restore(session))
            repo.insert(session, listOf(items[0], edited), setOf(items[0].key))
            withContext(Dispatchers.IO) {
                assertEquals(new, appDb.httpTTSDao.get(id))
                assertEquals(old, appDb.httpTTSDao.get(id + 1))
            }
            assertTrue(repo.restore(session)!!.committed)
        } finally {
            withContext(Dispatchers.IO) { appDb.httpTTSDao.delete(new, old) }
            File(context.cacheDir, "http-tts-import/$session.json").delete()
        }
    }
    @Test fun actualUriAndSingleJsonInputProduceSamePayloadAndInvalidCodeDoesNotWrite() = runBlocking {
        val source = File(context.cacheDir, "tts-source-${UUID.randomUUID()}.json")
        val item = HttpTTS(System.nanoTime(), "URI engine", "url", lastUpdateTime = 1)
        val repo = AppHttpTtsImportRepository(context)
        try {
            withContext(Dispatchers.IO) { source.writeText(GSON.toJson(item)) }
            assertEquals(repo.read(GSON.toJson(item)), repo.read(android.net.Uri.fromFile(source).toString()))
            assertTrue(runCatching { repo.edit("stable", "invalid") }.isFailure)
            withContext(Dispatchers.IO) { assertNull(appDb.httpTTSDao.get(item.id)) }
        } finally { source.delete() }
    }
}
