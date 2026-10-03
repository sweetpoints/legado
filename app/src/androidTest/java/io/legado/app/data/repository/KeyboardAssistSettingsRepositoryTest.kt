package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.KeyboardAssist
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class KeyboardAssistSettingsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase; private lateinit var directory: File
    private lateinit var repository: RoomKeyboardAssistSettingsRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "keyboard-settings-${UUID.randomUUID()}")
        repository = RoomKeyboardAssistSettingsRepository(context, database, directory)
    }
    @After fun cleanup() { database.close(); directory.deleteRecursively() }
    private suspend fun insert(vararg items: KeyboardAssist) = withContext(Dispatchers.IO) { database.keyboardAssistsDao.insert(*items) }
    @Test fun allTypesAndDuplicateKeysHaveDistinctStableIdsAndEditingConvertsToTypeZeroPreservingOrder() = runBlocking {
        insert(KeyboardAssist(0, "Same", "Zero", 1), KeyboardAssist(1, "Same", "One", 7))
        val rows = repository.observe().first(); assertEquals(2, rows.size); assertNotEquals(rows[0].id, rows[1].id)
        val session = UUID.randomUUID().toString(); val draft = repository.loadEditor(session, rows[1].id)
        val edited = draft.copy(key = KeyboardAssistSettingsText("New"), value = KeyboardAssistSettingsText(" Raw "), revision = 1)
        repository.writeEditor(session, edited); val saved = repository.saveEditor(session, edited)
        assertFalse(saved.open)
        val actual = repository.observe().first(); assertEquals(listOf(0, 0), actual.map { it.type })
        assertEquals("Zero", actual[0].value); assertEquals("New", actual[1].key); assertEquals(" Raw ", actual[1].value); assertEquals(7, actual[1].order)
    }
    @Test fun blankKeysAndDuplicateInsertFollowRoomReplaceAndDeleteOnlyTouchesExactPrimaryKey() = runBlocking {
        insert(KeyboardAssist(0, "", "Existing", 3), KeyboardAssist(1, "", "Other", 4))
        val session = UUID.randomUUID().toString(); val draft = repository.loadEditor(session, null)
            .copy(key = KeyboardAssistSettingsText(""), value = KeyboardAssistSettingsText(""), revision = 1)
        repository.saveEditor(session, draft)
        val rows = repository.observe().first(); assertEquals(2, rows.size); assertEquals("", rows.single { it.type == 0 }.value)
        assertEquals(5, rows.single { it.type == 0 }.order)
        repository.delete(rows.single { it.type == 1 }.id); repository.delete(rows.single { it.type == 1 }.id)
        assertEquals(listOf(0), repository.observe().first().map { it.type })
    }
    @Test fun reorderPersistsEverySerialNumberAndRejectsChangedCandidateSetWithoutPartialWrites() = runBlocking {
        insert(KeyboardAssist(0, "A", "a", 3), KeyboardAssist(1, "B", "b", 8), KeyboardAssist(0, "C", "c", 9))
        val before = repository.observe().first(); val order = before.reversed().map { it.id }
        repository.reorder(order); val changed = repository.observe().first()
        assertEquals(listOf("C", "B", "A"), changed.map { it.key }); assertEquals(listOf(1, 2, 3), changed.map { it.order })
        insert(KeyboardAssist(0, "D", "d", 10))
        assertTrue(runCatching { repository.reorder(order) }.isFailure)
        assertEquals(listOf(1, 2, 3, 10), repository.observe().first().map { it.order })
    }
    @Test fun largeDiskDraftAndSelectionRestoreWithoutRoomChangesAndStaleWritesCannotReopenCanceledEditor() = runBlocking {
        val session = UUID.randomUUID().toString(); val original = repository.loadEditor(session, null)
        val edited = original.copy(key = KeyboardAssistSettingsText(" Key ", 4, 1), value = KeyboardAssistSettingsText("Value".repeat(40000), 22, 3), revision = 3)
        repository.writeEditor(session, edited); repository.writeEditor(session, original)
        assertEquals(edited, repository.loadEditor(session, null)); assertTrue(repository.observe().first().isEmpty())
        repository.writeEditor(session, edited.copy(open = false, revision = 4)); repository.writeEditor(session, edited.copy(revision = 999))
        assertFalse(repository.loadEditor(session, null).open); assertTrue(repository.observe().first().isEmpty())
    }
    @Test fun journalFailureDoesNotMutateRoomAndPostDatabaseFailureRecoversWithoutOverwritingLaterChanges() = runBlocking {
        var stage = "beforeJournal"
        val repo = RoomKeyboardAssistSettingsRepository(context, database, directory) { current -> if (current == stage) error("Injected") }
        val session = UUID.randomUUID().toString(); val draft = repo.loadEditor(session, null).copy(key = KeyboardAssistSettingsText("Key"), value = KeyboardAssistSettingsText("Value"), revision = 1)
        assertTrue(runCatching { repo.saveEditor(session, draft) }.isFailure); assertTrue(repository.observe().first().isEmpty())
        stage = "afterDatabase"; assertTrue(runCatching { repo.saveEditor(session, draft) }.isFailure)
        assertEquals("Value", repository.observe().first().single().value)
        stage = ""; assertFalse(repo.loadEditor(session, null).open)
        insert(KeyboardAssist(0, "Key", "Later", 1)); repo.saveEditor(session, draft)
        assertEquals("Later", repository.observe().first().single().value)
    }
}
