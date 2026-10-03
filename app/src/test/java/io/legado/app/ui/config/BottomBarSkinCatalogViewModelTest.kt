package io.legado.app.ui.config

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BottomBarSkinCatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<BottomBarSkinCatalogViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : BottomBarSkinCatalogRepository {
        var names = listOf("A", "B"); var active = "A"; var fail = false; var gate: CompletableDeferred<Unit>? = null
        val deleted = mutableListOf<String>(); val activated = mutableListOf<String>(); val discarded = mutableListOf<String>()
        var imports = 0; var edits = 0; var zips = 0
        override suspend fun load() = BottomBarSkinCatalog(names, active)
        override suspend fun preview(name: String, sizePx: Int) = emptyList<Bitmap>()
        override suspend fun activate(name: String): String { activated += name; gate?.await(); if (fail) error("Activate failed"); active = name; return name }
        override suspend fun delete(name: String) { if (fail) error("Delete failed"); deleted += name; names = names - name; if (active == name) active = "" }
        override suspend fun importZip(uri: String): BottomBarSkinStaged { imports++; gate?.await(); if (fail) throw BottomBarSkinCatalogException(BottomBarSkinCatalogIssue.NoImages, Exception()); return BottomBarSkinStaged("session", " Imported ") }
        override suspend fun edit(name: String): BottomBarSkinStaged { edits++; gate?.await(); return BottomBarSkinStaged("session", name, name) }
        override suspend fun zip(name: String): String { zips++; if (fail) error("Missing skin"); return "/private/cache/$name.zip" }
        override suspend fun discard(session: String) { discarded += session }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = BottomBarSkinCatalogViewModel(repo, saved, 24).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun activateDefaultAndNamedSkinEmitOneSmallEventAndIgnoreUnknownOrPendingDuplicates() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.activate("Missing"); model.activate(""); model.activate("B"); runCurrent()
        assertEquals(listOf(""), repo.activated); assertEquals("", model.state.value.active)
        val effect = model.state.value.effect!!; assertEquals(BottomBarSkinCatalogEffectType.Changed, effect.type)
        assertNull(model.delivered("Wrong")); assertEquals(effect, model.delivered(effect.id)); assertNull(model.delivered(effect.id)); model.changedDelivered(effect.id)
        model.activate("B"); runCurrent(); assertEquals("B", model.state.value.active)
    }
    @Test fun longPressDeleteNeedsConfirmationAndFailureLeavesRetryableDialog() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.menu(""); assertNull(model.state.value.menu)
        model.menu("A"); model.requestDelete("A"); model.cancelDelete(); assertTrue(repo.deleted.isEmpty())
        model.requestDelete("A"); repo.fail = true; model.confirmDelete(); runCurrent(); assertEquals("A", model.state.value.delete); assertFalse(model.state.value.busy)
        repo.fail = false; model.confirmDelete(); model.confirmDelete(); runCurrent()
        assertEquals(listOf("A"), repo.deleted); assertEquals(listOf("B"), model.state.value.names); assertEquals("", model.state.value.active); assertNull(model.state.value.delete)
    }
    @Test fun importResultRequiresPickerTicketAndDuplicateResultCannotCreateAnotherSession() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.importResult("uri"); assertEquals(0, repo.imports)
        model.importPicker(); val picker = model.delivered(model.state.value.effect!!.id)!!; assertEquals(BottomBarSkinCatalogEffectType.Import, picker.type)
        model.importResult("uri"); model.importResult("duplicate"); runCurrent()
        assertEquals(1, repo.imports); val assigned = model.state.value.effect!!
        assertEquals(BottomBarSkinCatalogEffectType.Assign, assigned.type); assertEquals("session", assigned.session); assertEquals(" Imported ", assigned.name)
        assertNull(assigned.editName); assertTrue(repo.discarded.isEmpty())
    }
    @Test fun pendingAssignmentRestoresSameTicketAndFailedNativeLaunchDiscardsOnlyOwnedSession() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.edit("B"); runCurrent()
        val effect = model.state.value.effect!!; model.stop(); val restored = model(repo, copy(saved)); runCurrent()
        assertEquals(effect, restored.state.value.effect); assertEquals(1, repo.edits); assertEquals("B", effect.editName)
        restored.delivered(effect.id); restored.deliveryFailed(effect); runCurrent()
        assertEquals(listOf("session"), repo.discarded); assertEquals(BottomBarSkinCatalogIssue.Invalid, restored.state.value.issue)
    }
    @Test fun exportUsesDiskPathAndCompletionRequiresExactOutstandingTicket() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.exportResult(true); assertNull(model.state.value.effect)
        model.export("A"); runCurrent(); val effect = model.state.value.effect!!; assertEquals("/private/cache/A.zip", effect.path)
        assertTrue(saved.keys().none { saved.get<Any?>(it) is ByteArray }); assertEquals(BottomBarSkinCatalogEffectType.Export, effect.type)
        model.delivered(effect.id); model.exportResult(true); val completed = model.state.value.effect!!
        assertEquals(BottomBarSkinCatalogEffectType.Exported, completed.type); model.delivered(completed.id); model.exportResult(true); assertNull(model.state.value.effect)
    }
    @Test fun shareUsesSameNativeFileContractAndFailureUnblocksFurtherActions() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); repo.fail = true; model.share("A"); runCurrent()
        assertFalse(model.state.value.busy); assertEquals(BottomBarSkinCatalogIssue.Invalid, model.state.value.issue)
        repo.fail = false; model.share("B"); runCurrent(); assertEquals(BottomBarSkinCatalogEffectType.Share, model.state.value.effect!!.type)
        assertEquals("/private/cache/B.zip", model.state.value.effect!!.path)
    }
    @Test fun lateStagingAfterOwnerStopDiscardsSessionAndNeverPublishesNavigation() = test {
        val repo = Fake(); repo.gate = CompletableDeferred(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent()
        model.edit("A"); runCurrent(); model.stop(); repo.gate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.effect); assertEquals(listOf("session"), repo.discarded)
        val restored = model(repo, copy(saved)); runCurrent(); assertEquals(2, repo.edits); assertEquals(BottomBarSkinCatalogEffectType.Assign, restored.state.value.effect!!.type)
    }
    @Test fun restoredMenusDropDeletedTargetsAndImportCancellationLeavesSkinsUntouched() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.menu("A"); first.scroll(3, 29)
        first.stop(); repo.names = listOf("B"); val restored = model(repo, copy(saved)); runCurrent()
        assertNull(restored.state.value.menu); assertEquals(3, restored.state.value.scroll); assertEquals(29, restored.state.value.offset)
        restored.importPicker(); restored.delivered(restored.state.value.effect!!.id); restored.importResult(null); restored.importResult("late"); runCurrent()
        assertEquals(0, repo.imports); assertTrue(repo.deleted.isEmpty())
    }
    @Test fun changedMutationBlocksCloseUntilPostEventReturnsAndWrongTicketCannotUnlock() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); repo.gate = CompletableDeferred()
        model.activate(""); assertTrue(model.state.value.closeBlocked); runCurrent(); assertTrue(model.state.value.busy)
        repo.gate!!.complete(Unit); runCurrent(); val changed = model.state.value.effect!!
        model.delivered(changed.id); assertTrue(model.state.value.closeBlocked)
        model.changedDelivered("wrong"); assertTrue(model.state.value.closeBlocked)
        model.changedDelivered(changed.id); assertFalse(model.state.value.closeBlocked)
        model.requestDelete("B"); model.confirmDelete(); runCurrent(); assertTrue(model.state.value.closeBlocked)
        val deleted = model.state.value.effect!!; model.delivered(deleted.id); model.changedDelivered(deleted.id); assertFalse(model.state.value.closeBlocked)
    }
    @Test fun failedMutationUnblocksAndRestoredInterruptedMutationEmitsRefreshInsteadOfTrappingClose() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); repo.fail = true; model.activate(""); runCurrent()
        assertFalse(model.state.value.closeBlocked); assertFalse(model.state.value.busy)
        val restored = model(Fake(), SavedStateHandle(mapOf("skinCatalog.closeBlocked" to true))); runCurrent()
        assertTrue(restored.state.value.closeBlocked); val effect = restored.state.value.effect!!; assertEquals(BottomBarSkinCatalogEffectType.Changed, effect.type)
        restored.delivered(effect.id); restored.changedDelivered(effect.id); assertFalse(restored.state.value.closeBlocked)
    }

}
