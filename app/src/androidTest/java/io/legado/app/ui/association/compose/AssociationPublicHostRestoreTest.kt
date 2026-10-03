package io.legado.app.ui.association.compose

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.FileAssociationSessionRepository
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.association.FileAssociationViewModel
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.association.OnLineImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the installed public entries, separately from the debug compatibility-model fixture. */
class AssociationPublicHostRestoreTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = FileAssociationSessionRepository(application)

    @Test
    fun preparedFileHostAdoptsPrivatePayloadBeforeDiscardingEveryLargeIntentField() = runBlocking {
        verifyPublicHost(FileAssociationActivity::class.java, AssociationHostKind.File)
    }

    @Test
    fun preparedOnlineHostAdoptsPrivatePayloadBeforeDiscardingEveryLargeIntentField() =
        runBlocking {
            verifyPublicHost(OnLineImportActivity::class.java, AssociationHostKind.Online)
        }

    @Test
    fun freshFileWrapperRestoresSavedUuidWithNewStoreAndChildFirstDefaultKey() = runBlocking {
        verifyFreshStore(FileAssociationViewModel::class.java, AssociationHostKind.File) {
            FileAssociationViewModel(application, it)
        }
    }

    @Test
    fun freshOnlineWrapperRestoresSavedUuidWithNewStoreAndChildFirstDefaultKey() = runBlocking {
        verifyFreshStore(OnLineImportViewModel::class.java, AssociationHostKind.Online) {
            OnLineImportViewModel(application, it)
        }
    }

    private suspend fun createSession(host: AssociationHostKind, body: String): String {
        val ticket =
            sessions.create(AssociationInput(host, AssociationInputKind.SharedText, text = body))
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(
                revision = 1,
                phase =
                    if (host == AssociationHostKind.File) AssociationPhase.Unsupported
                    else AssociationPhase.ReadConfig,
                unsupportedName = "unsupported.fixture",
                unsupportedUri = "content://fixture/unsupported",
                readConfigFile = "private/config.json",
            ),
        )
        return ticket
    }

    private suspend fun <T : AssociationComposeActivity> verifyPublicHost(
        activityClass: Class<T>,
        host: AssociationHostKind,
    ) {
        val privateBody = "complete private metadata".repeat(40_000)
        val launchBody = "large intent body".repeat(3_000)
        val ticket = createSession(host, privateBody)
        val launchIntent =
            Intent(application, activityClass)
                .putExtra(AssociationImportViewModel.TICKET_KEY, ticket)
                .putExtra(Intent.EXTRA_TEXT, launchBody)
                .putExtra("unknownLargeMetadata", launchBody)
                .setData(Uri.parse("content://fixture/$launchBody"))
                .apply { clipData = ClipData.newPlainText("shared", launchBody) }
        val scenario = ActivityScenario.launch<T>(launchIntent)
        try {
            awaitCondition {
                var adopted = false
                scenario.onActivity {
                    adopted =
                        it.importModel.state.value.loaded &&
                            it.intent.data == null &&
                            it.intent.clipData == null
                }
                adopted
            }
            lateinit var original: AssociationImportViewModel
            scenario.onActivity { activity ->
                original = activity.importModel
                assertEquals(
                    setOf(AssociationImportViewModel.TICKET_KEY),
                    activity.intent.extras!!.keySet(),
                )
                assertEquals(ticket, original.ownedTicket)
                assertEquals(privateBody, original.state.value.session!!.input.text)
                val saved = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, saved)
                assertSmallSavedState(saved, launchBody)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(original, activity.importModel)
                assertEquals(ticket, activity.importModel.ownedTicket)
                assertFalse(activity.intent.hasExtra("unknownLargeMetadata"))
            }
            assertEquals(privateBody, sessions.read(ticket).input.text)
        } finally {
            scenario.close()
            sessions.release(ticket)
        }
    }

    private suspend fun <T : AssociationImportViewModel> verifyFreshStore(
        wrapperClass: Class<T>,
        host: AssociationHostKind,
        create: (SavedStateHandle) -> T,
    ) {
        val body = "private process restore body".repeat(40_000)
        val ticket = createSession(host, body)
        var firstOwner: RegistryOwner? = null
        var secondOwner: RegistryOwner? = null
        lateinit var firstModel: T
        lateinit var restoredModel: T
        lateinit var firstHandle: SavedStateHandle
        lateinit var restoredHandle: SavedStateHandle
        try {
            withContext(Dispatchers.Main) {
                firstOwner = RegistryOwner(null)
                val firstProvider =
                    provider(checkNotNull(firstOwner), wrapperClass, ticket, body) {
                        firstHandle = it
                        create(it)
                    }
                // activityViewModels uses this exact default concrete-wrapper key. Request it
                // before the host access, then verify the second lookup cannot create a peer VM.
                firstModel = firstProvider[wrapperClass]
                assertSame(firstModel, firstProvider[wrapperClass])
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), firstHandle.keys())
            }
            awaitCondition { firstModel.state.value.session != null }
            val saved =
                withContext(Dispatchers.Main) {
                    checkNotNull(firstOwner).save().also { checkNotNull(firstOwner).destroy() }
                }
            assertSmallSavedState(saved, body)
            withContext(Dispatchers.Main) {
                secondOwner = RegistryOwner(saved)
                restoredModel =
                    provider(checkNotNull(secondOwner), wrapperClass, null, body) {
                        restoredHandle = it
                        create(it)
                    }[wrapperClass]
                assertNotSame(firstModel, restoredModel)
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), restoredHandle.keys())
                assertEquals(
                    ticket,
                    restoredHandle.get<String>(AssociationImportViewModel.TICKET_KEY),
                )
            }
            awaitCondition { restoredModel.state.value.session != null }
            assertEquals(body, restoredModel.state.value.session!!.input.text)
        } finally {
            withContext(Dispatchers.Main) {
                firstOwner?.destroy()
                secondOwner?.destroy()
            }
            sessions.release(ticket)
        }
    }

    private fun <T : ViewModel> provider(
        owner: RegistryOwner,
        wrapperClass: Class<T>,
        initialTicket: String?,
        ignoredIntentBody: String,
        create: (SavedStateHandle) -> T,
    ): ViewModelProvider {
        val extras =
            MutableCreationExtras().apply {
                this[SAVED_STATE_REGISTRY_OWNER_KEY] = owner
                this[VIEW_MODEL_STORE_OWNER_KEY] = owner
                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = application
                this[DEFAULT_ARGS_KEY] =
                    Bundle().apply { putString(Intent.EXTRA_TEXT, ignoredIntentBody) }
            }
        val fallback = object : ViewModelProvider.Factory {}
        val factory = AssociationViewModelFactory(fallback, wrapperClass, { initialTicket }, create)
        return ViewModelProvider(owner.viewModelStore, factory, extras)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(20)
        }
    }

    private fun assertSmallSavedState(saved: Bundle, body: String) {
        fun inspect(bundle: Bundle) {
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION") val value = bundle.get(key)
                if (value is Bundle) inspect(value)
                if (value is String) assertFalse(value.contains(body))
            }
        }
        inspect(saved)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            assertTrue(parcel.dataSize() < 64 * 1024)
        } finally {
            parcel.recycle()
        }
    }

    private class RegistryOwner(restored: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry = controller.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            controller.performAttach()
            enableSavedStateHandles()
            controller.performRestore(restored)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun save(): Bundle = Bundle().also(controller::performSave)

        fun destroy() {
            viewModelStore.clear()
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
