package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RoomSearchScopeStoreTest {
    @Test fun enabledGroupsButAllSourcesAndAllFourSearchFieldsMatchLegacyDaoContract() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val enabled = BookSource(bookSourceUrl = "https://alpha.invalid", bookSourceName = "Alpha", bookSourceGroup = "Enabled group", bookSourceComment = "Unique comment", customOrder = 9)
            val disabled = BookSource(bookSourceUrl = "https://disabled.invalid", bookSourceName = "Disabled", bookSourceGroup = "Disabled group", enabled = false, customOrder = 1)
            withContext(Dispatchers.IO) { database.bookSourceDao.insert(enabled, disabled) }
            val repository = DefaultSearchScopeRepository(RoomSearchScopeStore(database))
            assertTrue(repository.groups().contains("Enabled group")); assertFalse(repository.groups().contains("Disabled group"))
            assertEquals(listOf(disabled.bookSourceUrl, enabled.bookSourceUrl), repository.sources("").first().map { it.url })
            for (query in listOf("ALPHA", "Enabled group", "alpha.invalid", "Unique comment")) {
                assertEquals(listOf(enabled.bookSourceUrl), repository.sources(query).first().map { it.url })
            }
            assertEquals("Disabled", repository.sources("Disabled").first().single().name)
            assertTrue(repository.sources("missing").first().isEmpty())
        } finally { database.close() }
    }
}
