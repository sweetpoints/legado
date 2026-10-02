package io.legado.app.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class RssSourceEditorAssistShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun assistRoomFlowFiltersTypeAndPreservesOrderingWithoutLeakingMutableEntities() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            withContext(Dispatchers.IO) { database.keyboardAssistsDao.insert(KeyboardAssist(0, "later", "@text", 2), KeyboardAssist(0, "first", "@js:", 1), KeyboardAssist(1, "other", "ignored", 0)) }
            val repository = RssSourceEditorAssistRepository(database); val initial = repository.keys().first()
            assertEquals(listOf("first", "later"), initial.map { it.key }); assertEquals("@js:", initial.first().value)
            withContext(Dispatchers.IO) { database.keyboardAssistsDao.insert(KeyboardAssist(0, "first", "updated", 1)) }
            assertEquals("updated", repository.keys().first().first().value); assertEquals("@js:", initial.first().value)
        } finally { database.close() }
    }
    @Test fun assistRowsPreferenceUpdatesLiveAndBoundsInvalidStoredValue() = runBlocking {
        val preferences = context.defaultSharedPreferences; val prior = preferences.getInt(PreferKey.showBoardLine, 1)
        val repository = RssSourceEditorAssistRepository()
        try {
            preferences.edit().putInt(PreferKey.showBoardLine, 1).commit()
            val updated = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { repository.preferences().first { it.rows == 3 } } }
            yield(); preferences.edit().putInt(PreferKey.showBoardLine, 3).commit(); assertEquals(3, updated.await().rows)
            preferences.edit().putInt(PreferKey.showBoardLine, 99).commit(); assertEquals(5, repository.preferences().first().rows)
        } finally { preferences.edit().putInt(PreferKey.showBoardLine, prior).commit() }
    }
    @Test fun generatedQrPngDecodesFullSourceJsonAndRepeatedShareKeepsBoundedOwnedArtifact() = runBlocking {
        val repository = RssSourceEditorShareRepository(context); val text = "{\"sourceUrl\":\"https://example.invalid\",\"sourceName\":\"Fixture\"}"
        val path = repository.qr(text)
        try {
            val decoded = withContext(Dispatchers.IO) { val bitmap = BitmapFactory.decodeFile(path); try { QRCodeUtils.parseCode(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() } }
            assertEquals(text, decoded); assertTrue(File(path).length() > 0); assertEquals(path, repository.qr("second"))
        } finally { File(path).delete() }
    }
}
