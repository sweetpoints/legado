package io.legado.app.ui.book.source.manage

import android.content.ContextWrapper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceManagerChannelContractTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookSourceManagerViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.viewModelScopeForTest() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun snapshotUsesStableNamesAndExcludesEffectsExportsAndFilePaths() {
        val row = row("opaque:id")
        val state = SourceManagerState(rows = listOf(row), selected = setOf(row.url),
            groups = listOf("group"), counts = mapOf("enabled" to 1), loading = false,
            feedback = SourceManagerFeedback("https://fixture.invalid", "summary", true, "synthetic-passphrase"),
            effect = SourceManagerEffect("id", "export", export = SourceExport(File("/private/native-secret-file"), "source.json", "application/json")))
        val json = sourceManagerSnapshotJson(state)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(1, obj["schemaVersion"].asInt)
        assertEquals("Default", obj["sort"].asString)
        assertTrue(obj["canMove"].asBoolean)
        assertTrue(obj["dialog"].isJsonNull)
        assertEquals("opaque:id", obj["rows"].asJsonArray[0].asJsonObject["url"].asString)
        assertEquals(true, obj["rows"].asJsonArray[0].asJsonObject["hasJs"].asBoolean)
        assertEquals("synthetic-passphrase", obj["feedback"].asJsonObject["passphrase"].asString)
        assertEquals(setOf("url", "name", "group", "order", "enabled", "exploreEnabled", "hasExplore", "hasLogin", "hasJs", "updated", "response", "weight", "checkStatus", "checkDetail", "host"), obj["rows"].asJsonArray[0].asJsonObject.keySet())
        assertFalse(obj.has("effect"))
        assertFalse(json.contains("native-secret-file"))
        assertFalse(json.contains("application/json"))
        assertFalse(json.contains("mainJs"))
    }

    @Test fun everyOriginalActionAndEnumIsAcceptedWithoutReflection() {
        sourceManagerAllowedActions.forEach { action ->
            assertEquals(action, parseSourceManagerCommand(mapOf("op" to "action", "action" to action, "key" to "")).value)
        }
        SourceMutation.entries.forEach {
            assertEquals(it, parseSourceManagerCommand(mapOf("op" to "mutation", "mutation" to it.name)).mutation)
        }
        BookSourceSort.entries.forEach {
            assertEquals(it, parseSourceManagerCommand(mapOf("op" to "sort", "value" to it.name)).sort)
        }
    }

    @Test fun invalidCommandsAreRejectedBeforeAnyDispatch() {
        val invalid = listOf<Any?>(null, "query", mapOf("op" to "unknown"),
            mapOf("op" to "action", "action" to "java.lang.Runtime"),
            mapOf("op" to "query", "value" to true),
            mapOf("op" to "selectAll", "value" to "unexpected"),
            mapOf("op" to "mutation", "mutation" to "delete"),
            mapOf("op" to "mutation", "mutation" to "DELETE", "key" to ""),
            mapOf("op" to "previewDrag", "index" to 1.5),
            mapOf("op" to "previewDrag", "index" to -1),
            mapOf("op" to "finishDrag", "cancelled" to "false"),
            mapOf("op" to "status", "value" to "CHECKING"),
            mapOf("op" to "slide", "keys" to listOf("a", "a"), "selectedAtStart" to emptyList<String>()))
        invalid.forEach { assertThrows(SourceManagerInvalidCommand::class.java) { parseSourceManagerCommand(it) } }
    }

    @Test fun utf8SnapshotLimitRejectsWithoutTruncating() {
        val state = SourceManagerState(draft = "源".repeat(6 * 1024 * 1024))
        val error = assertThrows(SourceManagerInvalidCommand::class.java) { sourceManagerSnapshotJson(state) }
        assertEquals("Source manager state exceeds 16 MiB", error.message)
        assertEquals(6 * 1024 * 1024, state.draft.length)
    }

    @Test fun commandMappingsPreserveDialogsOrderDirectionAndDuplicateMutationAcceptance() = runTest(dispatcher) {
        val repository = Repository()
        val model = model(repository)
        runCurrent()
        parseSourceManagerCommand(mapOf("op" to "action", "action" to "delete", "key" to "a")).apply(model)
        assertEquals(SourceManagerDialog.DELETE, model.state.value.dialog)
        model.dismiss()
        handleSourceManagerAction(model, "descending")
        handleSourceManagerAction(model, "top", "a")
        runCurrent()
        assertEquals(listOf(SourceMutation.BOTTOM), repository.mutations)
        handleSourceManagerMutation(model, SourceMutation.ADD_GROUP, "a")
        assertEquals(SourceManagerDialog.ADD_GROUP, model.state.value.dialog)
        model.dismiss()
        repository.gate = CompletableDeferred()
        parseSourceManagerCommand(mapOf("op" to "mutation", "mutation" to "DISABLE", "key" to "a")).apply(model)
        parseSourceManagerCommand(mapOf("op" to "mutation", "mutation" to "DISABLE", "key" to "a")).apply(model)
        runCurrent()
        assertEquals(1, repository.mutations.count { it == SourceMutation.DISABLE })
        repository.gate!!.complete(Unit)
        runCurrent()
    }

    @Test fun slideClearsHiddenSelectionsAndStateRemainsOwnedByNativeViewModel() = runTest(dispatcher) {
        val model = model(Repository())
        runCurrent()
        model.selectAll()
        model.query("Alpha")
        runCurrent()
        assertEquals(setOf("a", "b"), model.state.value.selected)
        parseSourceManagerCommand(mapOf("op" to "slide", "keys" to emptyList<String>(), "selectedAtStart" to emptyList<String>())).apply(model)
        assertTrue(model.state.value.selected.isEmpty())
        parseSourceManagerCommand(mapOf("op" to "action", "action" to "back")).apply(model)
        assertEquals("back", model.state.value.effect!!.action)
        runCurrent()
    }

    @Test fun errorBackRetainsDurableCancellationAndRestorationWithoutRepeatingAcceptedEffects() = runTest(dispatcher) {
        val storage = MemorySession()
        val manager = model(Repository(), storage)
        runCurrent()
        manager.effect("help")
        val oldId = manager.state.value.effect!!.id
        manager.effect("back")
        assertEquals(oldId, manager.state.value.effect!!.id)
        manager.hostError("synthetic preparation failure")
        assertFalse(canDispatchSourceManagerEffects(manager.state.value))
        manager.effect("back")
        val backId = manager.state.value.effect!!.id
        assertNotEquals(oldId, backId)
        assertEquals("back", manager.state.value.effect!!.action)
        assertTrue(canDispatchSourceManagerEffects(manager.state.value))
        assertFalse(canDispatchSourceManagerEffects(manager.state.value.copy(busy = true)))
        manager.effect("back")
        assertEquals(backId, manager.state.value.effect!!.id)
        runCurrent()
        var launches = 0
        assertFalse(manager.deliverEffect(backId, { false }) { launches++ })
        assertEquals(backId, manager.state.value.effect!!.id)
        assertFalse(storage.value.receipts.contains(backId))
        val restored = model(Repository(), storage)
        runCurrent()
        assertEquals(backId, restored.state.value.effect!!.id)
        assertTrue(restored.deliverEffect(backId, { true }) { launches++ })
        assertFalse(restored.deliverEffect(backId, { true }) { launches++ })
        assertEquals(1, launches)
        assertTrue(storage.value.receipts.contains(backId))
        assertFalse(storage.value.receipts.contains(oldId))
        val afterAcceptance = model(Repository(), storage)
        runCurrent()
        assertNull(afterAcceptance.state.value.effect)
    }

    @Test fun errorBackNeverPreemptsAnAcceptedBusyMutation() = runTest(dispatcher) {
        val repository = Repository().apply { gate = CompletableDeferred() }
        val model = model(repository)
        runCurrent()
        model.mutate(SourceMutation.DISABLE, listOf("a"))
        model.hostError("synthetic mutation failure")
        model.effect("back")
        assertNull(model.state.value.effect)
        assertFalse(canDispatchSourceManagerEffects(model.state.value.copy(effect = SourceManagerEffect("back", "back"))))
        runCurrent()
        repository.gate!!.complete(Unit)
        runCurrent()
    }

    @Test fun closingAndReattachingBridgeCancelsPreviewWithoutMovingOrLosingNativeSessionState() = runTest(dispatcher) {
        val repository = Repository()
        repository.rows.value = repository.rows.value.map { it.copy(name = "Alpha ${it.url}") }
        val manager = model(repository)
        runCurrent()
        manager.query("Alpha")
        runCurrent()
        manager.toggle("a")
        val context = ContextWrapper(null)
        val bridge = SourceManagerChannelBridge(context, FakeMessenger(), manager)
        manager.beginDrag("a")
        manager.previewDrag(1)
        assertEquals(listOf("b", "a"), manager.state.value.rows.map { it.url })
        bridge.close()
        bridge.close()
        runCurrent()
        assertNull(manager.state.value.draggingKey)
        assertEquals(listOf("a", "b"), manager.state.value.rows.map { it.url })
        assertEquals("Alpha", manager.state.value.query)
        assertEquals(setOf("a"), manager.state.value.selected)
        assertTrue(repository.moves.isEmpty())
        val restoredBridge = SourceManagerChannelBridge(context, FakeMessenger(), manager)
        var snapshot = ""
        restoredBridge.onMethodCall(MethodCall("snapshot", null), object : MethodChannel.Result {
            override fun success(result: Any?) { snapshot = result as String }
            override fun error(code: String, message: String?, details: Any?) { fail(code) }
            override fun notImplemented() { fail() }
        })
        assertTrue(JsonParser.parseString(snapshot).asJsonObject["draggingKey"].isJsonNull)
        manager.beginDrag("b")
        assertEquals("b", manager.state.value.draggingKey)
        manager.previewDrag(0)
        manager.finishDrag(cancelled = false)
        runCurrent()
        assertEquals(listOf(Triple("b", "a", false)), repository.moves)
        restoredBridge.close()
    }

    private class FakeMessenger : BinaryMessenger {
        override fun send(channel: String, message: ByteBuffer?) {}
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {}
    }

    private fun model(repository: Repository, storage: MemorySession = MemorySession()) = BookSourceManagerViewModel(repository, storage, object : SourceManagerPreferences {
        override suspend fun read() = SourceManagerPreferenceSnapshot()
        override suspend fun write(value: SourceManagerPreferenceSnapshot) {}
    }, dispatcher).also { models += it }

    private class MemorySession : SourceManagerSessionStorage {
        var value = SourceManagerSession()
        override fun read() = value
        override fun write(session: SourceManagerSession) { value = session }
        override fun delete() {}
    }

    private class Repository : BookSourceManagerRepository {
        val rows = MutableStateFlow(listOf(row("a"), row("b").copy(name = "Beta", order = 1)))
        val mutations = mutableListOf<SourceMutation>()
        val moves = mutableListOf<Triple<String, String, Boolean>>()
        var gate: CompletableDeferred<Unit>? = null
        override fun sources(query: String) = rows.map { it.filter { row -> query.isEmpty() || row.name.contains(query) } }
        override fun allSources() = rows
        override fun groups() = MutableStateFlow(emptyList<String>())
        override fun counts() = MutableStateFlow(emptyMap<String, Int>())
        override suspend fun mutate(keys: List<String>, action: SourceMutation, value: String) { mutations += action; gate?.await() }
        override suspend fun move(key: String, target: String, after: Boolean) { moves += Triple(key, target, after) }
        override suspend fun export(keys: List<String>) = SourceExport(File("unused"), "unused", "text/plain")
        override suspend fun resolve(keys: List<String>) = emptyList<io.legado.app.data.entities.BookSourcePart>()
        override suspend fun importHistory() = emptyList<String>()
        override suspend fun rememberImport(value: String) {}
        override suspend fun forgetImport(value: String) {}
        override suspend fun feedback(url: String) = SourceManagerFeedback(url, "", false)
        override suspend fun passphrase(url: String) = ""
    }
    companion object {
        private fun row(key: String) = SourceManagerRow(key, "Alpha", null, 0, true, true, true, false, true, 42L, 9L, 1, "NEEDS_CHECK", "", "fixture.invalid")
    }
}

private fun BookSourceManagerViewModel.viewModelScopeForTest() {
    androidx.lifecycle.ViewModelStore().also { it.put("model", this); it.clear() }
}
