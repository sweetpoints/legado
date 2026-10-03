package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ReplaceEditorAssistRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build() }
    @After fun cleanup() { database.close() }
    @Test fun actualRoomKeyboardProjectionFiltersTypeAndKeepsSerialOrderAndUpdates() = runBlocking {
        val repository = ReplaceEditorAssistRepository(database)
        withContext(Dispatchers.IO) { database.keyboardAssistsDao.insert(
            KeyboardAssist(0, "Later", "2", 2), KeyboardAssist(1, "Other", "3", 0), KeyboardAssist(0, "First", "1", 1)) }
        assertEquals(listOf(ReplaceEditorAssist("First", "1"), ReplaceEditorAssist("Later", "2")), repository.keys().first())
        withContext(Dispatchers.IO) { database.keyboardAssistsDao.update(KeyboardAssist(0, "First", "changed", 1)) }
        assertEquals("changed", repository.keys().first().first().value)
    }
    @Test fun rowPreferenceEmitsLatestValueWithoutDependingOnOtherListenerOrder() = runBlocking {
        val preferences = context.defaultSharedPreferences; val before = preferences.all[PreferKey.showBoardLine] as Int?
        try {
            preferences.edit().putInt(PreferKey.showBoardLine, 2).commit()
            val ready = CompletableDeferred<Unit>(); val repository = ReplaceEditorAssistRepository(database)
            val values = async(Dispatchers.Main) { repository.rows().onEach { if (it == 2) ready.complete(Unit) }.take(2).toList() }
            ready.await(); preferences.edit().putInt(PreferKey.showBoardLine, 5).commit()
            assertEquals(listOf(2, 5), withTimeout(5000) { values.await() })
        } finally {
            val edit = preferences.edit(); if (before == null) edit.remove(PreferKey.showBoardLine) else edit.putInt(PreferKey.showBoardLine, before); edit.commit()
        }
    }
}
