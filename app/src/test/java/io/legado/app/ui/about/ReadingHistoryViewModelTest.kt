package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val identity = ReadingHistoryIdentity("Title", "Author")
    private fun row(id: ReadingHistoryIdentity = identity) = ReadingHistoryRow(id, id.author, listOf(id.author), false, 3000, 1, "Chapter", ReadingHistoryCover(null, null, null, false))
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Drafts : ReadingHistoryDraftRepository {
        val values = mutableMapOf<String, ReadingHistoryDraft>()
        var fail = false
        override suspend fun create() = UUID.randomUUID().toString().also { values[it] = ReadingHistoryDraft() }
        override suspend fun read(ticket: String) = checkNotNull(values[ticket])
        override suspend fun write(ticket: String, draft: ReadingHistoryDraft) { check(!fail) { "Disk failure" }; val old = checkNotNull(values[ticket]); if (draft.revision > old.revision) values[ticket] = draft }
        override suspend fun release(ticket: String) { values.remove(ticket) }
    }
    private class Repo(var rows: List<ReadingHistoryRow>) : ReadingHistoryRepository {
        var prefs = ReadingHistoryPreferences()
        var load: (suspend (String) -> ReadingHistorySnapshot)? = null
        var removeFailure = false
        var preferenceWrite: (suspend (ReadingHistoryPreference) -> Unit)? = null
        var preferenceRead: (suspend () -> ReadingHistoryPreferences)? = null
        val preferenceValues = mutableListOf<Pair<ReadingHistoryPreference, ReadingHistoryPreferences>>()
        val deletes = mutableListOf<ReadingHistoryIdentity?>()
        val removedAuthors = mutableListOf<Pair<ReadingHistoryIdentity, String>>()
        var destination: suspend () -> ReadingHistoryDestination = { ReadingHistoryDestination(ReadingHistoryReader.Text, "book-url", "Title") }
        override suspend fun load(query: String, sort: Int) = load?.invoke(query) ?: ReadingHistorySnapshot(rows.filter { query.isEmpty() || it.identity.name.contains(query) }, rows.size, rows.sumOf { it.readTime }, rows.take(3))
        override suspend fun preferences() = preferenceRead?.invoke() ?: prefs
        override suspend fun preferences(value: ReadingHistoryPreferences, fields: Set<ReadingHistoryPreference>) {
            fields.forEach { field ->
                preferenceValues.add(field to value); preferenceWrite?.invoke(field)
                prefs = when (field) {
                ReadingHistoryPreference.Enabled -> prefs.copy(enabled = value.enabled)
                ReadingHistoryPreference.Simple -> prefs.copy(simple = value.simple)
                ReadingHistoryPreference.Days -> prefs.copy(days = value.days)
                ReadingHistoryPreference.Seconds -> prefs.copy(seconds = value.seconds)
                ReadingHistoryPreference.Fixed -> prefs.copy(fixed = value.fixed)
                ReadingHistoryPreference.Sort -> prefs.copy(sort = value.sort)
            } }
        }
        override suspend fun clear() { check(!removeFailure) { "Delete failed" }; deletes.add(null); rows = emptyList() }
        override suspend fun delete(identity: ReadingHistoryIdentity) { check(!removeFailure) { "Delete failed" }; deletes.add(identity); rows = rows.filter { it.identity != identity } }
        override suspend fun removeAuthor(identity: ReadingHistoryIdentity, author: String) { removedAuthors.add(identity to author) }
        override suspend fun destination(identity: ReadingHistoryIdentity) = destination()
    }
    @Test fun restoredLargeSearchAndConfirmationStayOffBundleAndDoNotDeleteUntilConfirmed() = runTest(dispatcher) {
        val drafts = Drafts(); val repo = Repo(listOf(row())); val saved = SavedStateHandle(); val model = ReadingHistoryViewModel(repo, drafts, saved)
        try { advanceUntilIdle(); model.delete(identity); model.query("z".repeat(250_000)); advanceUntilIdle(); model.flush()
            assertEquals(setOf("history.ticket"), saved.keys()); assertTrue(saved.get<String>("history.ticket")!!.length < 40)
            val restored = ReadingHistoryViewModel(repo, drafts, SavedStateHandle(mapOf("history.ticket" to saved.get<String>("history.ticket"))))
            try { advanceUntilIdle(); assertEquals(250_000, restored.state.value.query.length); assertEquals(identity, restored.state.value.confirmation?.identity); assertTrue(repo.deletes.isEmpty()) }
            finally { restored.stop(); advanceUntilIdle() }
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun nonCooperativeObsoleteSearchCannotOverwriteCurrentQueryResult() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val gate = CompletableDeferred<Unit>(); val drafts = Drafts(); val model = ReadingHistoryViewModel(repo, drafts, SavedStateHandle())
        try { advanceUntilIdle(); repo.load = { query -> if (query == "old") withContext(NonCancellable) { gate.await() }; ReadingHistorySnapshot(listOf(row(ReadingHistoryIdentity(query, "A"))), 1, 3000, emptyList()) }
            model.query("old"); runCurrent(); model.query("new"); runCurrent(); assertEquals("new", model.state.value.snapshot.rows.single().identity.name)
            gate.complete(Unit); advanceUntilIdle(); assertEquals("new", model.state.value.snapshot.rows.single().identity.name)
        } finally { gate.complete(Unit); model.stop(); advanceUntilIdle() }
    }
    @Test fun globalsStayUnfilteredAndResumeReadsChangesFromAnotherHost() = runTest(dispatcher) {
        val repo = Repo(listOf(row(), row(ReadingHistoryIdentity("Other", "B")))); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); model.query("Title"); advanceUntilIdle(); assertEquals(1, model.state.value.snapshot.rows.size); assertEquals(2, model.state.value.snapshot.count); assertEquals(6000L, model.state.value.snapshot.total)
            repo.prefs = repo.prefs.copy(days = true, fallback = "night"); model.resume(); advanceUntilIdle(); assertTrue(model.state.value.preferences.days); assertEquals("night", model.state.value.preferences.fallback)
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun deletingSameTitleUsesExactIdentityAndCancelNeverWritesRoom() = runTest(dispatcher) {
        val other = ReadingHistoryIdentity("Title", "Other"); val repo = Repo(listOf(row(), row(other))); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); model.delete(identity); model.dismissConfirmation(); advanceUntilIdle(); assertTrue(repo.deletes.isEmpty())
            model.delete(identity); model.confirmDelete(); model.confirmDelete(); advanceUntilIdle(); assertEquals(listOf(identity), repo.deletes); assertEquals(other, model.state.value.snapshot.rows.single().identity); assertNull(model.state.value.confirmation)
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun deleteFailureKeepsConfirmationForExplicitRetry() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); repo.removeFailure = true; model.clear(); model.confirmDelete(); advanceUntilIdle(); assertEquals("Delete failed", model.state.value.error); assertNotNull(model.state.value.confirmation)
            repo.removeFailure = false; model.confirmDelete(); advanceUntilIdle(); assertEquals(listOf(null), repo.deletes); assertEquals(0, model.state.value.snapshot.count)
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun authorChooserOnlyAcceptsOneOfMultipleLabelsAndPreservesEncodedIdentity() = runTest(dispatcher) {
        val combined = row(ReadingHistoryIdentity("Title", "encoded")).copy(combined = true, legacyAuthors = listOf("A", "B")); val repo = Repo(listOf(combined)); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); model.delete(combined.identity); model.chooseAuthor(); model.confirmDelete(); advanceUntilIdle(); assertTrue(repo.removedAuthors.isEmpty()); assertTrue(model.state.value.confirmation!!.chooseAuthor)
            model.removeAuthor("unknown"); assertTrue(model.state.value.confirmation!!.chooseAuthor)
            model.removeAuthor("A"); model.confirmDelete(); advanceUntilIdle(); assertEquals(listOf(combined.identity to "A"), repo.removedAuthors)
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun queuedDifferentPreferenceFieldsDoNotOverwriteEachOtherOrAnExternalChange() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); val original = model.state.value.preferences; repo.prefs = repo.prefs.copy(enabled = false)
            model.preference(ReadingHistoryPreference.Simple, original.copy(simple = false)); model.preference(ReadingHistoryPreference.Days, original.copy(days = true)); advanceUntilIdle()
            assertFalse(model.state.value.preferences.simple); assertTrue(model.state.value.preferences.days); assertFalse(model.state.value.preferences.enabled)
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun pendingNavigationRestoresButDurableConsumePreventsDuplicateReaderLaunch() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val drafts = Drafts(); val saved = SavedStateHandle(); val model = ReadingHistoryViewModel(repo, drafts, saved)
        try { advanceUntilIdle(); model.open(identity); advanceUntilIdle(); val id = model.state.value.navigation!!.id
            val restored = ReadingHistoryViewModel(repo, drafts, SavedStateHandle(mapOf("history.ticket" to saved.get<String>("history.ticket"))))
            try { advanceUntilIdle(); assertEquals(id, restored.state.value.navigation!!.id); assertEquals("book-url", restored.consumeNavigation(id)!!.key); assertNull(restored.consumeNavigation(id))
                assertNull(drafts.read(saved.get<String>("history.ticket")!!).navigation)
            } finally { restored.stop(); advanceUntilIdle() }
        } finally { model.stop(); advanceUntilIdle() }
    }
    @Test fun navigationWriteFailureKeepsPageAndNeverPublishesAnUnsafeLaunch() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val drafts = Drafts(); val model = ReadingHistoryViewModel(repo, drafts, SavedStateHandle())
        try { advanceUntilIdle(); drafts.fail = true; model.open(identity); advanceUntilIdle(); assertNull(model.state.value.navigation); assertEquals("Disk failure", model.state.value.error)
            drafts.fail = false; model.open(identity); advanceUntilIdle(); assertNotNull(model.state.value.navigation)
        } finally { drafts.fail = false; model.stop(); advanceUntilIdle() }
    }
    @Test fun stopRejectsNonCooperativeLateNavigationWithoutUiEvents() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val gate = CompletableDeferred<Unit>(); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try { advanceUntilIdle(); repo.destination = { withContext(NonCancellable) { gate.await() }; ReadingHistoryDestination(ReadingHistoryReader.Text, "late", "Title") }
            model.open(identity); runCurrent(); val before = model.state.value; model.stop(); gate.complete(Unit); advanceUntilIdle(); assertEquals(before, model.state.value); assertNull(model.state.value.navigation)
        } finally { gate.complete(Unit); model.stop(); advanceUntilIdle() }
    }
    @Test fun rapidDoubleToggleIsOptimisticAndPersistsTrueThenFalseDespiteFirstWriteGate() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val gate = CompletableDeferred<Unit>(); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try {
            advanceUntilIdle(); repo.preferenceWrite = { gate.await() }
            model.preference(ReadingHistoryPreference.Days, model.state.value.preferences.copy(days = true)); assertTrue(model.state.value.preferences.days); runCurrent()
            model.preference(ReadingHistoryPreference.Days, model.state.value.preferences.copy(days = false)); assertFalse(model.state.value.preferences.days)
            gate.complete(Unit); advanceUntilIdle(); assertFalse(model.state.value.preferences.days); assertFalse(repo.prefs.days)
            assertEquals(listOf(true, false), repo.preferenceValues.map { it.second.days })
        } finally { gate.complete(Unit); model.stop(); advanceUntilIdle() }
    }
    @Test fun lateResumePreferenceReadCannotUndoNewerOptimisticInput() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val gate = CompletableDeferred<Unit>(); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try {
            advanceUntilIdle(); val old = repo.prefs; repo.preferenceRead = { repo.preferenceRead = null; withContext(NonCancellable) { gate.await() }; old }
            val resume = launch { model.resume() }; runCurrent()
            model.preference(ReadingHistoryPreference.Days, model.state.value.preferences.copy(days = true)); assertTrue(model.state.value.preferences.days)
            gate.complete(Unit); runCurrent(); repo.preferenceRead = null; resume.join(); advanceUntilIdle()
            assertTrue(model.state.value.preferences.days); assertTrue(repo.prefs.days)
        } finally { gate.complete(Unit); model.stop(); advanceUntilIdle() }
    }
    @Test fun failedFieldSurvivesUnrelatedSuccessAndResumeUntilExplicitDeltaRetry() = runTest(dispatcher) {
        val repo = Repo(listOf(row())); val model = ReadingHistoryViewModel(repo, Drafts(), SavedStateHandle())
        try {
            advanceUntilIdle(); repo.preferenceWrite = { if (it == ReadingHistoryPreference.Days) error("Preference write failed") }
            model.preference(ReadingHistoryPreference.Days, model.state.value.preferences.copy(days = true)); model.preference(ReadingHistoryPreference.Simple, model.state.value.preferences.copy(simple = false)); advanceUntilIdle()
            assertTrue(model.state.value.preferencesDirty); assertTrue(model.state.value.preferences.days); assertFalse(model.state.value.preferences.simple); assertFalse(repo.prefs.days)
            model.resume(); advanceUntilIdle(); assertTrue(model.state.value.preferences.days)
            repo.preferenceWrite = null; model.retry(); advanceUntilIdle(); assertTrue(repo.prefs.days); assertFalse(model.state.value.preferencesDirty)
        } finally { model.stop(); advanceUntilIdle() }
    }

}
