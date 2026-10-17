package io.legado.app.ui.book.source.manage

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceManagerChannelBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val nonce = UUID.randomUUID().toString()
    private val key = "fixture:manager-$nonce"
    private lateinit var model: BookSourceManagerViewModel
    private val owner = ViewModelStore()
    private val messenger = FakeMessenger()
    private var bridge: SourceManagerChannelBridge? = null

    @Before fun setup() {
        runBlocking(Dispatchers.IO) {
            appDb.bookSourceDao.insert(BookSource(bookSourceUrl = key, bookSourceName = "Alpha $nonce",
                header = "synthetic-private-header", mainJs = "export default {}"))
        }
        instrumentation.runOnMainSync {
            model = BookSourceManagerViewModel(AppBookSourceManagerRepository(context), MemorySession(), object : SourceManagerPreferences {
                override suspend fun read() = SourceManagerPreferenceSnapshot()
                override suspend fun write(value: SourceManagerPreferenceSnapshot) {}
            })
            owner.put("manager", model)
            bridge = SourceManagerChannelBridge(context, messenger, model)
        }
        waitUntil { !model.state.value.loading && !model.state.value.busy }
        request("command", mapOf("op" to "query", "value" to nonce))
        waitUntil { !model.state.value.loading && model.state.value.rows.any { it.url == key } }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { bridge?.close(); owner.clear() }
        runBlocking(Dispatchers.IO) { SourceHelp.deleteBookSource(key) }
    }

    @Test fun snapshotIsLightweightAndMutationUsesTheExistingRoomRepository() {
        val snapshot = requireNotNull(request("snapshot").value as? String)
        val root = JsonParser.parseString(snapshot).asJsonObject
        assertEquals(1, root["schemaVersion"].asInt)
        assertEquals(key, root["rows"].asJsonArray.single().asJsonObject["url"].asString)
        assertTrue(root["rows"].asJsonArray.single().asJsonObject["hasJs"].asBoolean)
        assertFalse(snapshot.contains("synthetic-private-header"))
        assertFalse(root.has("effect"))
        assertNull(request("command", mapOf("op" to "mutation", "mutation" to "DISABLE", "key" to key)).error)
        waitUntil { runBlocking(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(key)?.enabled == false } }
        waitUntil { !model.state.value.busy }
        request("command", mapOf("op" to "mutation", "mutation" to "ADD_GROUP", "key" to key))
        assertEquals(SourceManagerDialog.ADD_GROUP, model.state.value.dialog)
        request("command", mapOf("op" to "draft", "value" to "Group $nonce"))
        request("command", mapOf("op" to "confirm"))
        waitUntil { runBlocking(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(key)?.bookSourceGroup == "Group $nonce" } }
        waitUntil { !model.state.value.busy }
    }

    @Test fun cancellingAndReattachingTheChannelPreservesTheActivityViewModelAndSession() {
        val events = LinkedBlockingQueue<String>()
        val sink = object : EventChannel.EventSink {
            override fun success(event: Any?) { events.offer(event as String) }
            override fun error(code: String, message: String?, details: Any?) { events.offer("error:$code") }
            override fun endOfStream() {}
        }
        instrumentation.runOnMainSync { bridge!!.listen(sink) }
        assertEquals(1, JsonParser.parseString(requireNotNull(events.poll(5, TimeUnit.SECONDS))).asJsonObject["schemaVersion"].asInt)
        request("command", mapOf("op" to "toggle", "key" to key))
        waitUntil { key in model.state.value.selected }
        instrumentation.runOnMainSync { bridge!!.cancelObservation() }
        events.clear()
        request("command", mapOf("op" to "draft", "value" to "durable draft"))
        instrumentation.waitForIdleSync()
        assertTrue(events.isEmpty())
        instrumentation.runOnMainSync { bridge!!.listen(sink) }
        assertEquals("durable draft", JsonParser.parseString(requireNotNull(events.poll(5, TimeUnit.SECONDS))).asJsonObject["draft"].asString)
        instrumentation.runOnMainSync { bridge!!.close() }
        assertTrue(messenger.handlers.values.all { it == null })
        assertEquals("closed", request("snapshot").error)
        assertEquals(setOf(key), model.state.value.selected)
        instrumentation.runOnMainSync { bridge = SourceManagerChannelBridge(context, messenger, model) }
        assertEquals("durable draft", JsonParser.parseString(request("snapshot").value as String).asJsonObject["draft"].asString)
        request("command", mapOf("op" to "slide", "keys" to emptyList<String>(), "selectedAtStart" to emptyList<String>()))
        assertTrue(model.state.value.selected.isEmpty())
    }

    @Test fun invalidCommandsAreRejectedWithoutChangingSharedDataOrEffects() {
        val bad = request("command", mapOf("op" to "action", "action" to "Runtime.exec", "key" to "synthetic-private"))
        assertEquals("invalid_request", bad.error)
        assertFalse(bad.message.orEmpty().contains("synthetic-private"))
        assertNull(model.state.value.effect)
        assertEquals("invalid_request", request("command", mapOf("op" to "finishDrag", "cancelled" to "false")).error)
        assertTrue(runBlocking(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(key)!!.enabled })
    }

    private data class Response(val value: Any?, val error: String? = null, val message: String? = null)
    private fun request(method: String, arguments: Any? = null): Response {
        val result = LinkedBlockingQueue<Response>()
        instrumentation.runOnMainSync { bridge!!.onMethodCall(MethodCall(method, arguments), object : MethodChannel.Result {
            override fun success(value: Any?) { result.offer(Response(value)) }
            override fun error(code: String, message: String?, details: Any?) { result.offer(Response(null, code, message)) }
            override fun notImplemented() { result.offer(Response(null, "not_implemented")) }
        }) }
        return requireNotNull(result.poll(5, TimeUnit.SECONDS))
    }
    private fun waitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < until) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Manager state did not settle")
    }
    private class MemorySession : SourceManagerSessionStorage {
        var value = SourceManagerSession()
        override fun read() = value
        override fun write(session: SourceManagerSession) { value = session }
        override fun delete() {}
    }
    private class FakeMessenger : BinaryMessenger {
        val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler?>()
        override fun send(channel: String, message: ByteBuffer?) {}
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) { handlers[channel] = handler }
    }
}
