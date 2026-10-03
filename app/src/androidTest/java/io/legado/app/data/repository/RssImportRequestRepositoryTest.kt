package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RssImportRequestRepositoryTest {
    @Test
    fun largeEditorDraftPersistsThroughRepositoryRecreationAndOnlyItsIdIsNeeded() =
        runBlocking(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AppRssImportRequestRepository(context)
            val request =
                RssImportRefreshRequest(
                    "stable-key",
                    "中文 JS library\n".repeat(100_000),
                    listOf(1, 4),
                    true,
                    false,
                )
            val id = repository.write(request)
            try {
                assertTrue(id.length < 100)
                assertEquals(request, AppRssImportRequestRepository(context).read(id))
                repository.remove(id)
                assertNull(repository.read(id))
            } finally {
                repository.remove(id)
            }
        }
}
