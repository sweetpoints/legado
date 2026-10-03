package io.legado.app.ui.book.import.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Server
import io.legado.app.data.repository.RoomRemoteServerListRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RemoteServerListRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun observedRowsPreserveSortAndDeleteRemovesOnlyRequestedIdentity() = runBlocking {
        val editor = io.legado.app.data.repository.RoomRemoteServerEditorRepository(database)
        editor.save(Server(1, "First", sortNumber = 2))
        editor.save(Server(2, "Second", sortNumber = 1))
        val repository = RoomRemoteServerListRepository(database)
        assertEquals(listOf(2L, 1L), repository.observe().first().map { it.id })
        repository.delete(2)
        assertEquals(listOf(1L), repository.observe().first().map { it.id })
        assertEquals("First", editor.load(1).name)
    }
}
