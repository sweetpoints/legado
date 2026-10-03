package io.legado.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSearchPreferencesFileTest {
    @Test
    fun scopeWritesKeepLegacyGroupCompanionAndOtherSyntheticPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-search-preferences-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val repository =
            DefaultBookSearchPreferencesRepository(
                AppBookSearchPreferencesStore(context, preferences)
            )
        try {
            preferences.edit().putString("unrelated", "kept").commit()
            val cases =
                listOf("One" to "One", "One,Two" to "", "Named::synthetic-url" to "", "" to "")
            for ((scope, group) in cases) {
                assertEquals(scope, repository.scope(scope).scope)
                assertEquals(scope, preferences.getString("searchScope", null))
                assertEquals(group, preferences.getString("searchGroup", null))
                assertEquals("kept", preferences.getString("unrelated", null))
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
