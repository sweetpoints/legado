package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.welcome.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun clear() {
        Dispatchers.resetMain()
    }

    private fun own(vm: WelcomeSettingsViewModel) = ViewModelStore().apply { put("vm", vm) }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun TestScope.ready(vm: WelcomeSettingsViewModel) {
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.failed)
    }

    @Test
    fun sliderAndButtonsUseMillisecondsAndLatestConflatedEditWithoutDisablingIndependentSwitches() =
        runTest(dispatcher) {
            val repo = Repo()
            val vm = WelcomeSettingsViewModel(repo, Inputs(), SavedStateHandle())
            val owner = own(vm)
            try {
                ready(vm)
                vm.milliseconds(500)
                vm.step(1)
                vm.step(1)
                runCurrent()
                assertEquals(502, repo.values.value.milliseconds)
                vm.milliseconds(900)
                runCurrent()
                assertEquals(800, vm.state.value.milliseconds)
                vm.milliseconds(-1)
                runCurrent()
                assertEquals(0, repo.values.value.milliseconds)
                vm.boolean(WelcomeSwitch.DayText, false)
                runCurrent()
                assertFalse(repo.values.value.switches.getValue(WelcomeSwitch.DayText))
                assertTrue(repo.values.value.switches.getValue(WelcomeSwitch.DayIcon))
                assertTrue(repo.values.value.switches.getValue(WelcomeSwitch.NightText))
            } finally {
                owner.clear()
            }
        }

    @Test
    fun oldOwnerFailedTimeFlushCannotOverrideNewerDialogEdit() =
        runTest(dispatcher) {
            val repo = Repo()
            val old = WelcomeSettingsViewModel(repo, Inputs(), SavedStateHandle())
            val first = own(old)
            val recent = WelcomeSettingsViewModel(repo, Inputs(), SavedStateHandle())
            val second = own(recent)
            try {
                ready(old)
                ready(recent)
                repo.failTime = true
                old.milliseconds(501)
                runCurrent()
                assertNotNull(old.state.value.error)
                repo.failTime = false
                recent.milliseconds(611)
                runCurrent()
                val count = repo.calls.size
                old.flush()
                assertEquals(count, repo.calls.size)
                assertEquals(611, repo.values.value.milliseconds)
            } finally {
                first.clear()
                second.clear()
            }
        }

    @Test
    fun savedUnwrittenMillisecondsRestoreWithoutAutomaticOverwriteAndExplicitRetryPersists() =
        runTest(dispatcher) {
            val repo = Repo()
            val saved = SavedStateHandle(mapOf("millisecondsEdit" to 613))
            val vm = WelcomeSettingsViewModel(repo, Inputs(), saved)
            val owner = own(vm)
            try {
                ready(vm)
                assertEquals(613, vm.state.value.milliseconds)
                assertNotNull(vm.state.value.error)
                assertTrue(repo.calls.isEmpty())
                vm.retry()
                runCurrent()
                assertEquals(613, repo.values.value.milliseconds)
                assertNull(saved.get<Int>("millisecondsEdit"))
            } finally {
                owner.clear()
            }
        }

    @Test
    fun nightPickerTicketSurvivesConsumedLaunchAndCodeZeroCallbackArrivingBeforeInitialization() =
        runTest(dispatcher) {
            val repo = Repo()
            val inputs = Inputs()
            val saved = SavedStateHandle(mapOf("pickerNight" to true))
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                vm.pickedImage("content:night", 0)
                runCurrent()
                assertEquals(listOf("image:true:content:night"), repo.calls)
                assertEquals("content:night", repo.values.value.nightImage)
                assertEquals("", repo.values.value.dayImage)
                assertNull(saved.get<Boolean>("pickerNight"))
            } finally {
                owner.clear()
            }
        }

    @Test
    fun initializationFailureRetainsEarlyNightImageForRetryInsteadOfDroppingOrWritingBlank() =
        runTest(dispatcher) {
            val repo = Repo()
            val inputs = Inputs().apply { failOpen = true }
            val saved = SavedStateHandle(mapOf("pickerNight" to true))
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                vm.pickedImage("content:early-night")
                runCurrent()
                assertTrue(vm.state.value.failed)
                assertTrue(repo.calls.isEmpty())
                inputs.failOpen = false
                vm.retry()
                runCurrent()
                assertEquals(listOf("image:true:content:early-night"), repo.calls)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun restoredPickerEventConsumesOnceButKeepsNightResultTicketUntilCallbackOrCancellation() =
        runTest(dispatcher) {
            val repo = Repo()
            val inputs = Inputs()
            val saved = SavedStateHandle()
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                ready(vm)
                vm.imageAction(true)
                val event = vm.state.value.picker!!
                val restoredSaved = copy(saved)
                val restored = WelcomeSettingsViewModel(repo, inputs, restoredSaved)
                val other = own(restored)
                try {
                    ready(restored)
                    assertEquals(event, restored.state.value.picker)
                    assertTrue(restored.consumePicker(event.id))
                    assertFalse(restored.consumePicker(event.id))
                    assertEquals(true, restoredSaved.get<Boolean>("pickerNight"))
                    restored.pickedImage(null, 0)
                    assertNull(restoredSaved.get<Boolean>("pickerNight"))
                    assertTrue(repo.calls.isEmpty())
                } finally {
                    other.clear()
                }
            } finally {
                owner.clear()
            }
        }

    @Test
    fun privateLargePickerInputRestoreDoesNotDownloadAutomaticallyAndRetriesUsingDiskRevisionBaseline() =
        runTest(dispatcher) {
            val uri = "https://example.com/" + "long-url".repeat(100000)
            val revision = System.nanoTime() + 1_000_000_000_000
            val inputs =
                Inputs().apply {
                    value = WelcomeImageDraft(WelcomeImageInput("incoming", true, uri), revision)
                }
            val saved = SavedStateHandle()
            val repo = Repo()
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                ready(vm)
                assertTrue(vm.state.value.imageRetry)
                assertTrue(repo.calls.isEmpty())
                assertFalse(saved.keys().any { saved.get<Any?>(it) == uri })
                vm.retry()
                runCurrent()
                assertEquals(listOf("image:true:$uri"), repo.calls)
                assertNull(inputs.value.input)
                assertTrue(inputs.value.revision > revision)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun failedImageInputWriteDoesNotApplyAndRetryAppliesExactlyOnce() =
        runTest(dispatcher) {
            val repo = Repo()
            val inputs = Inputs()
            val saved = SavedStateHandle(mapOf("pickerNight" to false))
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                ready(vm)
                inputs.failWrite = true
                vm.pickedImage("content:day")
                runCurrent()
                assertTrue(vm.state.value.imageRetry)
                assertTrue(repo.calls.isEmpty())
                inputs.failWrite = false
                vm.retry()
                runCurrent()
                assertEquals(listOf("image:false:content:day"), repo.calls)
                assertNull(inputs.value.input)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun successfulImageWithFailedInputClearRetriesOnlyReceiptWithoutApplyingAgain() =
        runTest(dispatcher) {
            val repo = Repo()
            val inputs = Inputs()
            val saved = SavedStateHandle(mapOf("pickerNight" to true))
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                ready(vm)
                inputs.failClear = true
                vm.pickedImage("https://image")
                runCurrent()
                assertTrue(vm.state.value.imageRetry)
                assertEquals(1, repo.calls.size)
                inputs.failClear = false
                vm.retry()
                runCurrent()
                assertEquals(listOf("image:true:https://image"), repo.calls)
                assertEquals("设定成功", vm.consumeMessage())
                assertNull(vm.consumeMessage())
                assertNull(inputs.value.input)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun stoppedOwnerRejectsNonCooperativeLateErrorAndCleanupReleasesOnlyItsInputSession() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repo = Repo().apply { imageGate = gate }
            val inputs = Inputs()
            val saved = SavedStateHandle(mapOf("pickerNight" to true))
            val vm = WelcomeSettingsViewModel(repo, inputs, saved)
            val owner = own(vm)
            try {
                ready(vm)
                vm.pickedImage("content:pending")
                runCurrent()
                val before = vm.state.value
                vm.stop()
                gate.complete(Unit)
                runCurrent()
                assertEquals(before, vm.state.value)
                vm.release()
                assertTrue(inputs.released)
            } finally {
                gate.complete(Unit)
                owner.clear()
            }
        }

    @Test
    fun wrongCodeCannotConsumeNightTicketAndEarlyResultClearsRestoredLaunchExactlyOnce() =
        runTest(dispatcher) {
            val saved = SavedStateHandle(mapOf("picker" to "old-launch", "pickerNight" to true))
            val repo = Repo()
            val vm = WelcomeSettingsViewModel(repo, Inputs(), saved)
            val owner = own(vm)
            try {
                vm.pickedImage("content:wrong", 221)
                assertEquals(true, saved.get<Boolean>("pickerNight"))
                vm.pickedImage("content:night", 0)
                vm.pickedImage("content:duplicate", 222)
                runCurrent()
                assertEquals(listOf("image:true:content:night"), repo.calls)
                assertNull(vm.state.value.picker)
                assertNull(saved.get<String>("picker"))
                assertNull(saved.get<Boolean>("pickerNight"))
            } finally {
                owner.clear()
            }
        }

    @Test
    fun cancelledResultConsumesTicketAndNewPickerAcceptsItsOwnSingleResult() =
        runTest(dispatcher) {
            val repo = Repo()
            val vm = WelcomeSettingsViewModel(repo, Inputs(), SavedStateHandle())
            val owner = own(vm)
            try {
                ready(vm)
                vm.picker(false)
                vm.pickedImage(null, 221)
                vm.pickedImage("content:late", 221)
                runCurrent()
                assertTrue(repo.calls.isEmpty())
                assertNull(vm.state.value.picker)
                vm.picker(true)
                vm.pickedImage("content:new", 222)
                vm.pickedImage("content:again", 222)
                runCurrent()
                assertEquals(listOf("image:true:content:new"), repo.calls)
            } finally {
                owner.clear()
            }
        }

    @Test
    fun restoredUnwrittenMillisecondsSurviveSuccessfulIndependentSwitchMutation() =
        runTest(dispatcher) {
            val saved = SavedStateHandle(mapOf("millisecondsEdit" to 643))
            val repo = Repo()
            val vm = WelcomeSettingsViewModel(repo, Inputs(), saved)
            val owner = own(vm)
            try {
                ready(vm)
                vm.boolean(WelcomeSwitch.NightIcon, false)
                runCurrent()
                assertEquals(643, vm.state.value.milliseconds)
                assertEquals(643, saved.get<Int>("millisecondsEdit"))
                assertEquals(500, repo.values.value.milliseconds)
                assertFalse(repo.values.value.switches.getValue(WelcomeSwitch.NightIcon))
                vm.retry()
                runCurrent()
                assertEquals(643, repo.values.value.milliseconds)
            } finally {
                owner.clear()
            }
        }

    private class Inputs : WelcomeImageInputRepository {
        var value = WelcomeImageDraft()
        var failOpen = false
        var failWrite = false
        var failClear = false
        var released = false

        override suspend fun open(session: String): WelcomeImageDraft {
            if (failOpen) error("open failed")
            check(!released)
            return value
        }

        override suspend fun write(session: String, draft: WelcomeImageDraft) {
            check(!released)
            if (failWrite || failClear && draft.input == null) error("write failed")
            if (draft.revision >= value.revision) value = draft
        }

        override suspend fun release(session: String) {
            released = true
        }
    }

    private class Repo : WelcomeSettingsRepository {
        val values = MutableStateFlow(WelcomeSettingsSnapshot())
        val calls = mutableListOf<String>()
        var failTime = false
        var imageGate: CompletableDeferred<Unit>? = null

        override fun observe(): Flow<WelcomeSettingsSnapshot> = values

        override suspend fun load() = values.value

        override suspend fun milliseconds(value: Int) {
            calls += "ms:$value"
            if (failTime) error("time write failed")
            values.value = values.value.copy(milliseconds = value)
        }

        override suspend fun boolean(key: WelcomeSwitch, value: Boolean) {
            calls += "bool:${key.name}:$value"
            values.value = values.value.copy(switches = values.value.switches + (key to value))
        }

        override suspend fun image(night: Boolean, uri: String?) {
            calls += "image:$night:$uri"
            imageGate?.let {
                withContext(NonCancellable) {
                    it.await()
                    error("late failure")
                }
            }
            values.value =
                if (night) values.value.copy(nightImage = uri.orEmpty())
                else values.value.copy(dayImage = uri.orEmpty())
        }
    }
}
