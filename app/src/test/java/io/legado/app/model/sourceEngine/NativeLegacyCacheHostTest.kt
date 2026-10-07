package io.legado.app.model.sourceEngine

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class NativeLegacyCacheHostTest {
    private class Backend : NativeLegacyCacheHost.Backend {
        val writes = mutableListOf<Triple<String, Any, Int>>()
        val memory = mutableMapOf<String, Any>()
        var onlyDisk: Boolean? = null
        override fun put(key: String, value: Any, seconds: Int) { writes.add(Triple(key, value, seconds)) }
        override fun get(key: String, onlyDisk: Boolean): String { this.onlyDisk = onlyDisk; return "fixture" }
        override fun delete(key: String) { memory.remove(key) }
        override fun putMemory(key: String, value: Any) { memory[key] = value }
        override fun getFromMemory(key: String) = memory[key]
        override fun deleteMemory(key: String) { memory.remove(key) }
        override fun getInt(key: String) = 7
        override fun getLong(key: String) = 8L
        override fun getDouble(key: String) = 1.25
        override fun getFloat(key: String) = 2.5f
        override fun getByteArray(key: String) = byteArrayOf(-1, 0, 127)
        override fun putFile(key: String, value: String, seconds: Int) { writes.add(Triple(key, value, seconds)) }
        override fun getFile(key: String) = "file-fixture"
    }
    @Test fun usesSecondsDefaultPersistenceAndExactDiskReadFlag() {
        val backend = Backend()
        val value = mapOf("kind" to "string", "value" to "fixture")
        NativeLegacyCacheHost.call("cacheHost.put", listOf("key", value), backend = backend)
        NativeLegacyCacheHost.call("cacheHost.put", listOf("key", value, 60.0), backend = backend)
        NativeLegacyCacheHost.call("cacheHost.putFile", listOf("key", "file", -1), backend = backend)
        assertEquals(listOf(0, 60, -1), backend.writes.map { it.third })
        assertEquals("fixture", NativeLegacyCacheHost.call("cacheHost.get", listOf("key", true), backend = backend))
        assertEquals(true, backend.onlyDisk)
    }
    @Test fun bytesStayDistinctFromJsonArraysAndMemoryRetainsNumericType() {
        val backend = Backend()
        NativeLegacyCacheHost.call("cacheHost.put", listOf("bytes", mapOf("kind" to "bytes", "value" to listOf(-1, 0, 127))), backend = backend)
        assertArrayEquals(byteArrayOf(-1, 0, 127), backend.writes.single().second as ByteArray)
        NativeLegacyCacheHost.call("cacheHost.putMemory", listOf("number", mapOf("kind" to "json", "value" to 7)), backend = backend)
        assertEquals(7, backend.memory["number"])
        assertEquals(mapOf("kind" to "json", "value" to 7), NativeLegacyCacheHost.call("cacheHost.getFromMemory", listOf("number"), backend = backend))
        assertEquals(mapOf("kind" to "bytes", "value" to listOf(-1, 0, 127)), NativeLegacyCacheHost.call("cacheHost.getByteArray", listOf("bytes"), backend = backend))
    }
    @Test fun badEnvelopesAndCancelledTasksDoNotWriteCache() {
        val backend = Backend()
        assertThrows(IllegalStateException::class.java) { NativeLegacyCacheHost.call("cacheHost.getClass", emptyList(), backend = backend) }
        assertThrows(IllegalArgumentException::class.java) { NativeLegacyCacheHost.call("cacheHost.put", listOf("key", mapOf("kind" to "bytes", "value" to listOf(256))), backend = backend) }
        assertThrows(IllegalArgumentException::class.java) { NativeLegacyCacheHost.call("cacheHost.put", listOf("key", mapOf("kind" to "string", "value" to "fixture"), 1.5), backend = backend) }
        val cancelled = Job().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { NativeLegacyCacheHost.call("cacheHost.put", listOf("key", mapOf("kind" to "string", "value" to "fixture")), cancelled, backend) }
        assertTrue(backend.writes.isEmpty())
    }
}
