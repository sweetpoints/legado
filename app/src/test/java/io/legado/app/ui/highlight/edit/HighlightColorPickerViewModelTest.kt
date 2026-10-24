package io.legado.app.ui.highlight.edit

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightColorPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun model(alpha: Boolean = true, saved: SavedStateHandle = SavedStateHandle()) = HighlightColorPickerViewModel(saved, 0x80fff176.toInt(), alpha, "nonce", listOf(0x80fff176.toInt(), 0xffff0000.toInt(), 0))
    @Test fun argbHighBitZeroAndNonAlphaHexPreserveTheOriginalColorContract() {
        val m = model(); m.hex("#80123456"); assertEquals(0x80123456.toInt(), m.state.value.value); assertFalse(m.state.value.invalid)
        m.hex("#FFFFFFFF"); assertEquals(-1, m.state.value.value)
        m.hex("#00000000"); assertEquals(0, m.state.value.value)
        m.hex("#-"); assertTrue(m.state.value.invalid); assertThrows(IllegalStateException::class.java) { m.finish(true) }
        val rgb = model(false); rgb.hex("123456"); assertEquals(0xff123456.toInt(), rgb.state.value.value)
        rgb.hex("#80123456"); assertTrue(rgb.state.value.invalid); assertThrows(IllegalArgumentException::class.java) { rgb.channel("A", 5) }
    }
    @Test fun sliderPresetAndInvalidHexDraftSurviveRecreationButCancelProducesNoSelectedResult() {
        val saved = SavedStateHandle(); val m = model(saved = saved)
        m.channel("A", 17); assertEquals(17, m.state.value.value ushr 24)
        m.channel("R", 255); m.channel("G", 0); m.channel("B", 127); assertEquals("#11FF007F", m.state.value.hex)
        m.preset(1); assertEquals(-65536, m.state.value.value); m.hex("#not-valid")
        val restored = model(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals("#not-valid", restored.state.value.hex); assertTrue(restored.state.value.invalid); assertEquals(-65536, restored.state.value.value)
        restored.finish(false); assertEquals(false to -65536, restored.consumeResult()); assertNull(restored.consumeResult())
    }
    @Test fun independentNonceAndMonotonicSequenceRejectReplayWhileReadyDeliveryIsExactlyOnce() = runTest(dispatcher) {
        val m = model(); val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; var ready = true; val results = mutableListOf<Pair<Boolean, Int>>()
        val c = HighlightColorPickerNativeCoordinator(m, host.lifecycle, backgroundScope, { ready }, emptyMap()) { save, value -> results += save to value }; val owner = c.bind()
        fun command(seq: Long, op: String, args: Map<String, Any?> = emptyMap()) = c.command(owner, args + mapOf("generation" to c.generation, "nonce" to "nonce", "sequence" to seq, "op" to op))
        try {
            assertThrows(IllegalArgumentException::class.java) { c.command(owner, mapOf("generation" to c.generation, "nonce" to "other", "sequence" to 1L, "op" to "cancel")) }
            command(1, "hex", mapOf("text" to "#80123456")); assertThrows(IllegalArgumentException::class.java) { command(1, "cancel") }
            ready = false; m.finish(true); runCurrent(); assertTrue(results.isEmpty())
            ready = true; c.onHostReady(); runCurrent(); assertEquals(listOf(true to 0x80123456.toInt()), results)
            c.onHostReady(); runCurrent(); assertEquals(1, results.size)
            c.close(); assertThrows(IllegalStateException::class.java) { command(2, "cancel") }
        } finally { c.close() }
    }
    @Test fun throwingCompletionReportsAndClosesWithoutReplayingConsumedColorResult() = runTest(dispatcher) {
        val m = model(); val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var results = 0; var closes = 0; var reports = 0
        val c = HighlightColorPickerNativeCoordinator(m, host.lifecycle, backgroundScope, { true }, emptyMap(),
            completionFailed = { reports++ }) { _, _ ->
                try { results++; throw IllegalStateException("style host unavailable") }
                finally { closes++ }
            }
        try {
            c.bind(); m.hex("#80123456"); m.finish(true); runCurrent()
            assertEquals(1, results); assertEquals(1, reports); assertEquals(1, closes)
            assertTrue(m.state.value.finished); assertTrue(m.state.value.saved); assertNull(m.consumeResult())
            c.onHostReady(); host.registry.currentState = Lifecycle.State.STARTED
            host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(1, results); assertEquals(1, closes)
        } finally { c.close() }
    }

}
