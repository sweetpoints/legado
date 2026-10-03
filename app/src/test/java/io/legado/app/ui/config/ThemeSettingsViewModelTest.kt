package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun savedCopy(value: SavedStateHandle) = SavedStateHandle(value.keys().associateWith { value.get<Any?>(it) })
    private fun own(vm: ThemeSettingsViewModel) = ViewModelStore().apply { put("vm", vm) }
    private fun TestScope.ready(vm: ThemeSettingsViewModel) = runCurrent().also { assertFalse(vm.state.value.loading); assertFalse(vm.state.value.failed) }
    @Test fun numericDraftUsesEffectiveDefaultsClampsAndOnlyConfirmationPersists() = runTest(dispatcher) {
        val repo = Repo(); val vm = ThemeSettingsViewModel(repo, Names(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.popup(ThemeSettingsPopup.Font); assertEquals(13, vm.state.value.number); vm.number(99); assertEquals(16, vm.state.value.number)
            vm.dismissPopup(); assertTrue(repo.actions.isEmpty()); vm.popup(ThemeSettingsPopup.Font); vm.confirm(default = true); runCurrent(); assertEquals(listOf("font:null"), repo.actions)
            vm.popup(ThemeSettingsPopup.Elevation); vm.number(-10); vm.confirm(); runCurrent(); assertEquals("elevation:0", repo.actions.last())
        } finally { owner.clear() }
    }
    @Test fun largeNameLivesOnlyInPrivateDraftAndDiskRevisionIsBaselineAfterProcessRestore() = runTest(dispatcher) {
        val repo = Repo(); val names = Names().apply { value = ThemeNameDraft("initial", System.nanoTime() + 1_000_000_000_000) }; val saved = SavedStateHandle()
        val vm = ThemeSettingsViewModel(repo, names, saved); val owner = own(vm)
        try { ready(vm); vm.popup(ThemeSettingsPopup.SaveNight); val text = "full arbitrary theme name".repeat(60000); vm.name(text); vm.flush()
            assertTrue(names.value.revision > System.nanoTime() + 100_000_000_000); assertFalse(saved.keys().any { saved.get<Any?>(it) == text })
            val restored = ThemeSettingsViewModel(repo, names, savedCopy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(text, restored.state.value.name); assertEquals(ThemeSettingsPopup.SaveNight, restored.state.value.popup)
                restored.confirm(); runCurrent(); assertEquals("save:true:$text", repo.actions.single())
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun observedExternalSettingsNeverOverwriteOpenEditedColorAndNumberDrafts() = runTest(dispatcher) {
        val repo = Repo(); val saved = SavedStateHandle(); val vm = ThemeSettingsViewModel(repo, Names(), saved); val owner = own(vm)
        try { ready(vm); vm.popup(ThemeSettingsPopup.Color, ThemeColor.DayPrimary); vm.color(0x123456)
            repo.values.value = repo.values.value.copy(colors = mapOf(ThemeColor.DayPrimary to -1), fontScale = 8); runCurrent()
            assertEquals(0xff123456.toInt(), vm.state.value.color); assertEquals(0xff123456.toInt(), saved.get<Int>("color"))
            vm.popup(ThemeSettingsPopup.Font); vm.number(15); repo.values.value = repo.values.value.copy(fontScale = 9); runCurrent(); assertEquals(15, vm.state.value.number)
        } finally { owner.clear() }
    }
    @Test fun invalidBackgroundKeepsDraftOpenAndAcceptingValidColorConsumesPopupBeforeRecreation() = runTest(dispatcher) {
        val repo = Repo(); val saved = SavedStateHandle(); val vm = ThemeSettingsViewModel(repo, Names(), saved); val owner = own(vm)
        try { ready(vm); vm.popup(ThemeSettingsPopup.Color, ThemeColor.DayBackground); vm.color(0xff000000.toInt()); vm.confirm(); runCurrent()
            assertEquals(ThemeSettingsProblem.DayTooDark, vm.state.value.problem); assertEquals(ThemeSettingsPopup.Color, vm.state.value.popup); assertTrue(repo.actions.isEmpty())
            vm.color(-1); vm.confirm(); assertNull(saved.get<String>("popup")); assertNull(vm.state.value.popup); runCurrent(); assertEquals("color:DayBackground:-1", repo.actions.single())
        } finally { owner.clear() }
    }
    @Test fun navigationAndImageRequestRestoreAndConsumeExactlyOnce() = runTest(dispatcher) {
        val repo = Repo(); val names = Names(); val saved = SavedStateHandle(); val vm = ThemeSettingsViewModel(repo, names, saved); val owner = own(vm)
        try { ready(vm); vm.popup(ThemeSettingsPopup.NightBackground); vm.destination(ThemeSettingsDestination.ImageNight)
            val event = vm.state.value.event!!; assertNull(vm.state.value.popup)
            val restoredSaved = savedCopy(saved); val restored = ThemeSettingsViewModel(repo, names, restoredSaved); val other = own(restored)
            try { ready(restored); assertEquals(event, restored.state.value.event); assertTrue(restored.consumeEvent(event.id)); assertFalse(restored.consumeEvent(event.id))
                val third = ThemeSettingsViewModel(repo, names, savedCopy(restoredSaved)); val thirdOwner = own(third)
                try { ready(third); assertNull(third.state.value.event) } finally { thirdOwner.clear() }
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun initialNameFailurePreventsAllWritesAndExplicitRetryRestoresOriginalName() = runTest(dispatcher) {
        val repo = Repo(); val names = Names().apply { failOpen = true; value = ThemeNameDraft("restored") }; val vm = ThemeSettingsViewModel(repo, names, SavedStateHandle()); val owner = own(vm)
        try { runCurrent(); assertTrue(vm.state.value.failed); vm.toggleNight(); vm.name("blank overwrite"); runCurrent(); assertTrue(repo.actions.isEmpty())
            names.failOpen = false; vm.retry(); ready(vm); assertEquals("restored", vm.state.value.name)
        } finally { owner.clear() }
    }
    @Test fun stoppedOwnerRejectsLateNonCooperativeErrorsAndReleasedSessionCannotResurrectDraft() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Repo().apply { late = gate }; val names = Names(); val vm = ThemeSettingsViewModel(repo, names, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.toggleNight(); runCurrent(); val before = vm.state.value; vm.stop(); gate.complete(Unit); runCurrent(); assertEquals(before, vm.state.value)
            vm.release(); assertTrue(names.released); vm.name("late"); runCurrent(); assertTrue(names.released)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun imageFailureDoesNotImplicitlyRetryAndFreshSelectionPublishesDownloadedMessageOnlyAfterSuccess() = runTest(dispatcher) {
        val repo = Repo().apply { failImage = true }; val vm = ThemeSettingsViewModel(repo, Names(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.background(false, "https://image"); runCurrent(); assertNotNull(vm.state.value.error); assertFalse(vm.state.value.downloaded)
            runCurrent(); assertEquals(1, repo.actions.size); repo.failImage = false; vm.background(true, "https://other"); runCurrent()
            assertTrue(vm.state.value.downloaded); vm.clearMessage(); assertFalse(vm.state.value.downloaded); assertNull(vm.state.value.error)
        } finally { owner.clear() }
    }
    private class Names : ThemeNameDraftRepository {
        var value = ThemeNameDraft(); var failOpen = false; var released = false
        override suspend fun open(session: String): ThemeNameDraft { if (failOpen) error("read failed"); check(!released); return value }
        override suspend fun write(session: String, draft: ThemeNameDraft) { check(!released); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) { released = true }
    }
    private class Repo : ThemeSettingsRepository {
        val values = MutableStateFlow(ThemeSettingsSnapshot(systemFontScale = 1.26f, elevation = 4,
            colors = mapOf(ThemeColor.DayPrimary to 0xff123456.toInt(), ThemeColor.DayBackground to -1)))
        val actions = mutableListOf<String>(); var late: CompletableDeferred<Unit>? = null; var failImage = false
        override fun observe(): Flow<ThemeSettingsSnapshot> = values
        override suspend fun load() = values.value
        override suspend fun boolean(key: ThemeSwitch, value: Boolean) { actions += "bool:${key.name}:$value" }
        override suspend fun color(key: ThemeColor, value: Int) { actions += "color:${key.name}:$value" }
        override suspend fun launcher(value: String) { actions += "launcher:$value" }
        override suspend fun elevation(value: Int?) { actions += "elevation:$value" }
        override suspend fun font(value: Int?) { actions += "font:$value" }
        override suspend fun toggleNight() { actions += "night"; late?.let { withContext(NonCancellable) { it.await(); error("late error") } } }
        override suspend fun saveTheme(night: Boolean, name: String) { actions += "save:$night:$name" }
        override suspend fun image(night: Boolean, uri: String?) { actions += "image:$night:$uri"; if (failImage) error("image failed") }
        override suspend fun refreshTheme(night: Boolean) { actions += "refresh:$night" }
    }
}
