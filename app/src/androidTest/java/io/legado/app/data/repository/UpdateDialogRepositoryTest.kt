package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.config.LocalConfig
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UpdateDialogRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = mutableListOf<String>()
    @After fun cleanup() { ids.forEach { File(context.filesDir, "update-dialog-requests/$it.json").delete() } }
    @Test fun immediatelyLoadedStagedRequestPreservesLargeMarkdownAndReleaseMetadataAcrossRepositoryInstances() = runBlocking {
        val request = UpdateDialogRequest("v1", "Log **Bold** ".repeat(20000), "primary", "app.apk", "backup", "mirror", "alternate", 1024, 1704067200000, true)
        val id = FileUpdateDialogRepository.stage(context, request); ids += id
        assertTrue(id.length < 100); assertEquals(request, FileUpdateDialogRepository(context).load(id))
        assertEquals(request, FileUpdateDialogRepository(context).load(id))
    }
    @Test fun realIgnorePreferenceIsPersistedWithoutModifyingRequest() = runBlocking {
        val before = LocalConfig.ignoreUpdateVersion
        try {
            val repository = FileUpdateDialogRepository(context); repository.ignore("test-version")
            assertEquals("test-version", LocalConfig.ignoreUpdateVersion)
        } finally { withContext(Dispatchers.IO) { LocalConfig.ignoreUpdateVersion = before } }
    }
}
