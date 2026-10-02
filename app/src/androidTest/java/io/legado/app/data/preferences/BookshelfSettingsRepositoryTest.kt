package io.legado.app.data.preferences

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.legado.app.constant.PreferKey
import org.junit.Assert.*
import org.junit.Test

class BookshelfSettingsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun legacyProgressLoadsWithoutWritingAndConfirmedChoiceUpdatesBothKeys() {
        val prefs = context.getSharedPreferences("compose-shelf-progress-test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putBoolean(PreferKey.showBookshelfReadProgress, false).commit()
            val repository = PreferenceBookshelfSettingsRepository(context, prefs)
            val initial = repository.load(); assertEquals(0, initial.progress)
            assertFalse(prefs.contains(PreferKey.bookshelfReadProgressMode))
            val effects = repository.commit(initial.copy(progress = 2))
            assertEquals(2, prefs.getInt(PreferKey.bookshelfReadProgressMode, -1)); assertTrue(prefs.getBoolean(PreferKey.showBookshelfReadProgress, false))
            assertEquals(1, effects.refreshCount)
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun openRepairsInvalidRangesWhileDefaultHeaderOptionsStayOff() {
        val prefs = context.getSharedPreferences("compose-shelf-range-test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putInt(PreferKey.bookGroupStyle, 7).putInt(PreferKey.bookshelfLayout, 9)
                .putInt(PreferKey.bookshelfSort, -1).putInt(PreferKey.showBooknameLayout, 8).commit()
            assertEquals(BookshelfSettingsDraft(), PreferenceBookshelfSettingsRepository(context, prefs).load())
            assertEquals(0, prefs.getInt(PreferKey.bookGroupStyle, -1)); assertEquals(0, prefs.getInt(PreferKey.bookshelfLayout, -1))
            assertFalse(prefs.getBoolean(PreferKey.showBookshelfRecentReading, false)); assertFalse(prefs.getBoolean(PreferKey.showBookshelfStats, false))
        } finally { prefs.edit().clear().commit() }
    }
}
