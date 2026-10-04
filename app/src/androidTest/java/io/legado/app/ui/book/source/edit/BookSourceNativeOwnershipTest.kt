package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookSourceNativeOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val directory = File(context.cacheDir, "book-native-${UUID.randomUUID()}")
    private val store = ViewModelStore()
    private val savedState = SavedStateHandle()
    private lateinit var model: BookSourceComposeViewModel

    @After
    fun cleanup() {
        compose.runOnIdle { store.clear() }
        database.close()
        directory.deleteRecursively()
    }

    private fun createModel(handle: SavedStateHandle = savedState): BookSourceComposeViewModel {
        lateinit var created: BookSourceComposeViewModel
        compose.runOnIdle {
            created =
                BookSourceComposeViewModel(
                    RoomBookSourceEditorRepository(
                        context,
                        database,
                        directory,
                        invalidate = { _, _ -> },
                    ),
                    handle,
                    null,
                )
            store.put(UUID.randomUUID().toString(), created)
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            created.state.value.document != null && !created.state.value.busy
        }
        return created
    }

    private fun prepareQr(): String {
        compose.runOnIdle { model.requestAction(BookSourceNativeAction.QR) }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.document?.nativeRequest?.action == BookSourceNativeAction.QR
        }
        return model.state.value.document!!.nativeRequest!!.id
    }

    @Test
    fun realRegistryLateOldOwnerCannotDeliverToNewQrAfterPaste() {
        model = createModel()
        val registry = RecordingRegistry()
        val oldId = prepareQr()
        lateinit var oldLauncher: ActivityResultLauncher<Unit?>
        compose.runOnIdle {
            oldLauncher =
                registry.register("book-source-native-$oldId", QrCodeResult()) {
                    model.qrReturned(oldId, it)
                }
        }
        deliver(oldId, oldLauncher)
        val oldCode = registry.lastRequestCode
        compose.runOnIdle { model.importText(GSON.toJson(BookSource("paste", "paste"))) }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.document?.form?.field(0, "bookSourceUrl")?.value == "paste"
        }
        compose.runOnIdle { oldLauncher.unregister() }
        val newId = prepareQr()
        lateinit var newLauncher: ActivityResultLauncher<Unit?>
        compose.runOnIdle {
            newLauncher =
                registry.register("book-source-native-$newId", QrCodeResult()) {
                    model.qrReturned(newId, it)
                }
        }
        deliver(newId, newLauncher)
        compose.runOnIdle {
            registry.dispatchResult(
                oldCode,
                Activity.RESULT_OK,
                Intent().putExtra("result", GSON.toJson(BookSource("stale", "stale"))),
            )
        }
        compose.runOnIdle {
            assertEquals(newId, model.state.value.document!!.nativeRequest!!.id)
            assertEquals(
                "paste",
                model.state.value.document!!.form.field(0, "bookSourceUrl")!!.value,
            )
        }
    }

    @Test
    fun realRegistryRestoresPrivateUuidOwnerAndConsumesPendingResultOnce() {
        model = createModel()
        val id = prepareQr()
        val firstRegistry = RecordingRegistry()
        val registryState = Bundle()
        lateinit var firstLauncher: ActivityResultLauncher<Unit?>
        compose.runOnIdle {
            firstLauncher =
                firstRegistry.register("book-source-native-$id", QrCodeResult()) {
                    model.qrReturned(id, it)
                }
        }
        deliver(id, firstLauncher)
        compose.runOnIdle {
            firstRegistry.onSaveInstanceState(registryState)
            firstLauncher.unregister()
        }
        val restored =
            createModel(
                SavedStateHandle(
                    mapOf("bookSourceDraftId" to savedState.get<String>("bookSourceDraftId"))
                )
            )
        val restoredRegistry = RecordingRegistry()
        compose.runOnIdle {
            restoredRegistry.onRestoreInstanceState(registryState)
            restoredRegistry.dispatchResult(
                firstRegistry.lastRequestCode,
                Activity.RESULT_OK,
                Intent().putExtra("result", GSON.toJson(BookSource("restored", "restored"))),
            )
            restoredRegistry.register("book-source-native-$id", QrCodeResult()) {
                restored.qrReturned(id, it)
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            restored.state.value.document?.form?.field(0, "bookSourceUrl")?.value == "restored"
        }
        compose.runOnIdle { assertEquals(null, restored.state.value.document!!.nativeRequest) }
        runBlocking {
            withContext(Dispatchers.IO) {
                assertEquals(
                    "restored",
                    RoomBookSourceEditorRepository(context, database, directory)
                        .readDraft(savedState.get<String>("bookSourceDraftId")!!)!!
                        .form
                        .field(0, "bookSourceUrl")!!
                        .value,
                )
            }
        }
    }

    private fun deliver(id: String, launcher: ActivityResultLauncher<Unit?>) {
        // Block only the instrumentation thread; Main must remain free for private IO resumes.
        runBlocking {
            withContext(Dispatchers.Main) {
                model.deliverNative(id, { true }, { launcher.launch(null) })
            }
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        var lastRequestCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
        }
    }
}
