package io.legado.app.ui.book.read

import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextActionMenuViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun primaryMoreAndDismissResetKeepSameImmutablePartitionAndCallbackAction() = runTest(dispatcher) {
        val repo = Fake(); val model = TextActionMenuViewModel(repo); model.refresh(); runCurrent()
        model.toggleMore(); assertTrue(model.state.value.more)
        model.invoke("more"); assertEquals(repo.snapshot.more.single(), model.state.value.events.single().action)
        model.reset(); assertFalse(model.state.value.more); assertTrue(model.state.value.events.isEmpty())
        assertEquals(repo.snapshot, model.state.value.snapshot)
    }
    @Test fun longPressChangesSpeakModeWithoutInvokingActionOrEditorAndConsumedToastDoesNotReplay() = runTest(dispatcher) {
        val repo = Fake(); val model = TextActionMenuViewModel(repo); model.refresh(); runCurrent(); model.longPress(); runCurrent()
        val toast = model.state.value.events.single(); assertEquals(TextActionEventKind.Toast, toast.kind); assertNull(toast.action)
        assertEquals("切换为从选择的地方开始一直朗读", toast.message); model.consume(toast.id); assertTrue(model.state.value.events.isEmpty())
        model.longPress(); runCurrent(); assertEquals("切换为朗读选择内容", model.state.value.events.single().message)
    }
    @Test fun editorIsIndependentAndUnknownActionIsIgnoredAndNoMoreDoesNotToggle() = runTest(dispatcher) {
        val repo = Fake().apply { snapshot = TextActionSnapshot(emptyList(), emptyList()) }
        val model = TextActionMenuViewModel(repo); model.refresh(); runCurrent()
        model.toggleMore(); assertFalse(model.state.value.more); model.invoke("unknown"); assertTrue(model.state.value.events.isEmpty())
        model.edit(); assertEquals(TextActionEventKind.Edit, model.state.value.events.single().kind)
    }
    @Test fun refreshCancelsLateOldLoadAndResetsMoreWithoutLosingNewestConfiguration() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }; val model = TextActionMenuViewModel(repo); model.refresh(); runCurrent()
        val old = repo.gate!!; repo.gate = null; repo.snapshot = TextActionSnapshot(listOf(TextAction("new", TextActionKind.Copy, "new")), emptyList())
        model.refresh(); runCurrent(); old.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.snapshot.primary.single().id); assertFalse(model.state.value.loading)
    }
    @Test fun discoveryFailureKeepsActionsAndProducesOriginalLocalizedPrefix() = runTest(dispatcher) {
        val repo = Fake().apply { snapshot = snapshot.copy(discoveryError = "detail") }; val model = TextActionMenuViewModel(repo)
        model.refresh(); runCurrent(); assertEquals(1, model.state.value.snapshot.primary.size)
        assertEquals("获取文字操作菜单出错:detail", model.state.value.events.single().message)
    }
    @Test fun ownerStoreClearCancelsOutstandingDiscoveryAndCannotPublishLateEvents() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }; val model = TextActionMenuViewModel(repo)
        val store = ViewModelStore().apply { put("menu", model) }
        model.refresh(); runCurrent(); store.clear(); repo.gate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.snapshot.primary.isEmpty()); assertTrue(model.state.value.events.isEmpty())
    }
    private class Fake : TextActionRepository {
        var snapshot = TextActionSnapshot(listOf(TextAction("primary", TextActionKind.Copy, "Copy")), listOf(TextAction("more", TextActionKind.Share, "Share")))
        var mode = 0; var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(): TextActionSnapshot { val captured = snapshot; gate?.await(); return captured }
        override suspend fun toggleSpeakMode(): Int { mode = if (mode == 0) 1 else 0; return mode }
    }
}
