package io.legado.app.ui.book.import.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Server
import io.legado.app.data.repository.RoomRemoteServerEditorRepository
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RemoteServerEditorRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomRemoteServerEditorRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomRemoteServerEditorRepository(database)
    }
    @After fun close() { database.close() }
    @Test fun replacingServerPreservesIdentityAndSortWithoutDuplicateRows() = runBlocking {
        repository.save(Server(7, "Old", config = "{}", sortNumber = 13))
        val edited = repository.load(7).copy(name = "New", config = "{\"password\":\"updated\"}")
        repository.save(edited)
        assertEquals(1, database.serverDao.all.size); assertEquals("New", repository.load(7).name)
        assertEquals(13, repository.load(7).sortNumber); assertEquals(edited.config, repository.load(7).config)
        assertTrue(runCatching { repository.load(999) }.isFailure)
    }
    @Test fun rejectedReplacementLeavesOriginalServerAvailable() = runBlocking {
        repository.save(Server(7, "Old", config = "{}"))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_server BEFORE INSERT ON servers WHEN NEW.name = 'rejected' BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        assertTrue(runCatching { repository.save(repository.load(7).copy(name = "rejected")) }.isFailure)
        assertEquals("Old", repository.load(7).name)
    }
}
