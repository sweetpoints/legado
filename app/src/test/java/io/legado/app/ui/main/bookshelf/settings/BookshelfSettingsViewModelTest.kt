package io.legado.app.ui.main.bookshelf.settings

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.*
import org.junit.Assert.*
import org.junit.Test

class BookshelfSettingsViewModelTest {
    private class Repository(var value: BookshelfSettingsDraft = BookshelfSettingsDraft()) : BookshelfSettingsRepository {
        val writes = mutableListOf<BookshelfSettingsDraft>()
        override fun load() = value.normalized()
        override fun commit(value: BookshelfSettingsDraft): BookshelfSettingsEffects {
            writes += value; return bookshelfSettingsEffects(this.value, value).also { this.value = value }
        }
    }
    @Test fun draftRestoresWithoutReloadOrWritingAndCancelNeverAppliesIt() {
        val repo = Repository(); val saved = SavedStateHandle(); val model = BookshelfSettingsViewModel(repo, saved)
        val draft = model.state.value.copy(layout = 6, sort = 5, title = 2, recent = true, margin = 42)
        model.edit(draft); assertTrue(repo.writes.isEmpty())
        repo.value = repo.value.copy(sort = 3)
        val restored = BookshelfSettingsViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(draft, restored.state.value)
        restored.cancel(); assertNull(restored.confirm()); restored.edit(draft.copy(margin = 12))
        assertTrue(repo.writes.isEmpty()); assertEquals(draft, restored.state.value)
    }
    @Test fun confirmAppliesOnceAndRestoredFinishedDialogCannotApplyAgain() {
        val repo = Repository(); val saved = SavedStateHandle(); val model = BookshelfSettingsViewModel(repo, saved)
        model.edit(model.state.value.copy(unread = false, latest = true, fastScroll = true, progress = 2, waitCount = true, sort = 5))
        val effects = model.confirm()!!
        assertEquals(4, effects.refreshCount); assertTrue(effects.updateSort); assertTrue(effects.updateWaitCount)
        assertFalse(effects.recreate); assertFalse(effects.notifyMain); assertNull(model.confirm())
        val restored = BookshelfSettingsViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertNull(restored.confirm()); assertEquals(1, repo.writes.size)
    }
    @Test fun exactEventPayloadsAndRecreatePrecedenceArePreserved() {
        val before = BookshelfSettingsDraft()
        val after = before.copy(groupStyle = 1, layout = 3, recent = true, stats = true, unread = false, progress = 0)
        val events = mutableListOf<Pair<String, Any>>()
        bookshelfSettingsEffects(before, after).dispatchEvents { tag, value -> events += tag to value }
        assertEquals(listOf(EventBus.BOOKSHELF_REFRESH to "", EventBus.BOOKSHELF_REFRESH to "", EventBus.RECREATE to ""), events)
        events.clear()
        bookshelfSettingsEffects(before, before.copy(groupStyle = 1)).dispatchEvents { tag, value -> events += tag to value }
        assertEquals(listOf(EventBus.NOTIFY_MAIN to false), events)
        assertTrue(events.single().second is Boolean)
        events.clear(); bookshelfSettingsEffects(before, before).dispatchEvents { tag, value -> events += tag to value }; assertTrue(events.isEmpty())
    }
    @Test fun allOptionsNormalizeToTheOriginalValidRanges() {
        val repo = Repository(); val model = BookshelfSettingsViewModel(repo, SavedStateHandle())
        model.edit(BookshelfSettingsDraft(groupStyle = 4, progress = -1, layout = 8, sort = 6, title = 9, margin = 99))
        assertEquals(BookshelfSettingsDraft(margin = 60), model.state.value)
        model.edit(model.state.value.copy(margin = -2)); assertEquals(0, model.state.value.margin)
        for (layout in 0..6) for (sort in 0..5) for (progress in 0..2) {
            model.edit(model.state.value.copy(layout = layout, sort = sort, progress = progress))
            assertEquals(layout, model.state.value.layout); assertEquals(sort, model.state.value.sort); assertEquals(progress, model.state.value.progress)
        }
    }
    @Test fun layoutTitleMarginRecentAndStatsRequestRecreationWhileSortDoesNot() {
        val before = BookshelfSettingsDraft()
        listOf(before.copy(layout = 1), before.copy(title = 2), before.copy(margin = 13), before.copy(recent = true), before.copy(stats = true)).forEach {
            assertTrue(bookshelfSettingsEffects(before, it).recreate)
        }
        assertEquals(1, bookshelfSettingsEffects(before, before.copy(layout = 1)).changedLayout)
        assertFalse(bookshelfSettingsEffects(before, before.copy(sort = 5)).recreate)
    }
}
