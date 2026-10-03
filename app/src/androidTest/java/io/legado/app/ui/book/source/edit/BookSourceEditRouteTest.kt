package io.legado.app.ui.book.source.edit

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import io.legado.app.R
import io.legado.app.data.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSourceEditRouteTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val directory = File(context.cacheDir, "book-route-${UUID.randomUUID()}")
    private val store = ViewModelStore()

    @After
    fun cleanup() {
        compose.runOnIdle { store.clear() }
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun predictiveBackKeepsDirtySourceUntilExplicitDiscardAndClosesOnlyOnce() {
        lateinit var model: BookSourceComposeViewModel
        var finishes = 0
        compose.runOnIdle {
            model =
                BookSourceComposeViewModel(
                    RoomBookSourceEditorRepository(
                        context,
                        database,
                        directory,
                        invalidate = { _, _ -> },
                    ),
                    SavedStateHandle(),
                    null,
                )
            store.put("editor", model)
        }
        compose.setContent {
            BookSourceEditRoute(model, {}, {}, { finishes++ }, {}, {}, { true }, false, 6, 1, false)
        }
        compose.waitUntil(10_000) { model.state.value.document != null && !model.state.value.busy }
        compose.runOnIdle { model.updateField(0, "bookSourceName", "dirty", 5, 5) }
        pressBack()
        compose.waitUntil { model.state.value.confirmDiscard }
        assertEquals(0, finishes)
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        compose.waitUntil(10_000) { model.state.value.document!!.finished }
        compose.waitUntil { finishes == 1 }
        runBlocking {
            withContext(Dispatchers.IO) {
                assertTrue(directory.listFiles()!!.any { it.name.endsWith(".json") })
            }
        }
    }
}
