package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.model.backup.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BackupSettingsRouteTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Settings : BackupSettingsRepository {
        val value = MutableStateFlow(BackupSettingsSnapshot())

        override fun observe(): Flow<BackupSettingsSnapshot> = value

        override suspend fun load() = value.value

        override suspend fun text(key: BackupSettingText, value: String) {}

        override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) {}

        override suspend fun automatic(value: AutoBackupSettings) {}

        override suspend fun path(value: String?) {}

        override suspend fun localPassword(value: String) {}

        override suspend fun needsHelp() = false
    }

    private class Drafts : BackupSettingsDraftRepository {
        var value = BackupSettingsDraft()

        override suspend fun open(session: String) = value

        override suspend fun write(session: String, draft: BackupSettingsDraft) {
            if (draft.revision >= value.revision) value = draft
        }

        override suspend fun release(session: String) {}
    }

    private class Choices : BackupChoicesRepository {
        override suspend fun load(group: BackupChoiceGroup) = emptyList<BackupChoice>()

        override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean) =
            emptyList<BackupChoice>()

        override suspend fun save() {}
    }

    private class Operations : BackupOperationsRepository {
        var backups = 0

        override suspend fun writableTree(path: String) = true

        override suspend fun backup(path: String?, uploadWebDav: Boolean) {
            backups++
        }

        override suspend fun restoreFiles() = BackupRestoreFiles(listOf("backup"), false)

        override suspend fun restoreWebDav(name: String) {}

        override suspend fun restoreLocal(uri: String) {}

        override suspend fun importOld(uri: String) {}
    }

    private class Lan : BackupLanRepository {
        override suspend fun prepare(): BackupLanOffer = error("unused")

        override suspend fun decode(qrText: String): BackupLanReceiveInfo = error("unused")

        override fun receive(qrText: String): Flow<BackupLanReceivePhase> = emptyFlow()

        override suspend fun close(id: String) {}

        override suspend fun closeAll() {}
    }

    @Test
    fun resumedHostDeliveryConsumesBeforeCallAndCannotRepeatAfterPauseOrCompositionRecreation() {
        lateinit var owner: Owner
        lateinit var model: BackupSettingsViewModel
        lateinit var controller: BackupOperationsController
        val store = ViewModelStore()
        val operations = Operations()
        val delivered = mutableListOf<String>()
        var successes = 0
        var attached by mutableStateOf(true)
        compose.runOnUiThread {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = BackupSettingsViewModel(Settings(), Choices(), Drafts(), SavedStateHandle())
            store.put("backup", model)
            controller = model.operations(operations, Lan())
        }
        val images =
            object : BackupLanImageRepository {
                override suspend fun load(path: String): android.graphics.Bitmap = error("unused")
            }
        try {
            compose.setContent {
                if (attached)
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        LegadoComposeTheme {
                            BackupSettingsRoute(
                                model,
                                controller,
                                images,
                                { true },
                                { event ->
                                    assertNull(controller.state.value.event)
                                    assertFalse(controller.consumeEvent(event.id))
                                    delivered += event.id
                                },
                                { successes++ },
                                {},
                            )
                        }
                    }
            }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }
            compose.runOnIdle { controller.localRestore() }
            compose.waitForIdle()
            assertTrue(delivered.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { delivered.size == 1 }
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                attached = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                attached = true
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            assertEquals(1, delivered.size)
            compose.runOnIdle {
                controller.result(
                    BackupHostAction.RestoreFile,
                    null,
                    controller.resultId(BackupHostAction.RestoreFile),
                )
                owner.registry.currentState = Lifecycle.State.STARTED
                controller.manualDestination(false)
            }
            compose.waitUntil(timeoutMillis = 10000) { controller.state.value.success != null }
            assertEquals(0, successes)
            assertEquals(1, operations.backups)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { successes == 1 }
            compose.runOnIdle { attached = false }
            compose.waitForIdle()
            compose.runOnIdle { attached = true }
            compose.waitForIdle()
            assertEquals(1, successes)
            assertEquals(1, operations.backups)
        } finally {
            compose.runOnIdle {
                attached = false
                owner.registry.currentState = Lifecycle.State.DESTROYED
                model.stop()
                store.clear()
            }
            runBlocking { model.release() }
        }
    }
}
