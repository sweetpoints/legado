package io.legado.app.model.sourceEngine

import io.legado.app.help.CacheManager
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive

/** Original bounded CacheManager; only explicit methods and JSON/byte values cross V8. */
object NativeLegacyCacheHost {
    val methods = setOf("cacheHost.put", "cacheHost.get", "cacheHost.delete", "cacheHost.putMemory",
        "cacheHost.getFromMemory", "cacheHost.deleteMemory", "cacheHost.getInt", "cacheHost.getLong",
        "cacheHost.getDouble", "cacheHost.getFloat", "cacheHost.getByteArray", "cacheHost.putFile", "cacheHost.getFile")

    interface Backend {
        fun put(key: String, value: Any, seconds: Int)
        fun get(key: String, onlyDisk: Boolean): String?
        fun delete(key: String)
        fun putMemory(key: String, value: Any)
        fun getFromMemory(key: String): Any?
        fun deleteMemory(key: String)
        fun getInt(key: String): Int?
        fun getLong(key: String): Long?
        fun getDouble(key: String): Double?
        fun getFloat(key: String): Float?
        fun getByteArray(key: String): ByteArray?
        fun putFile(key: String, value: String, seconds: Int)
        fun getFile(key: String): String?
    }
    private object Original : Backend {
        override fun put(key: String, value: Any, seconds: Int) = CacheManager.put(key, value, seconds)
        override fun get(key: String, onlyDisk: Boolean) = CacheManager.get(key, onlyDisk)
        override fun delete(key: String) = CacheManager.delete(key)
        override fun putMemory(key: String, value: Any) = CacheManager.putMemory(key, value)
        override fun getFromMemory(key: String) = CacheManager.getFromMemory(key)
        override fun deleteMemory(key: String) = CacheManager.deleteMemory(key)
        override fun getInt(key: String) = CacheManager.getInt(key)
        override fun getLong(key: String) = CacheManager.getLong(key)
        override fun getDouble(key: String) = CacheManager.getDouble(key)
        override fun getFloat(key: String) = CacheManager.getFloat(key)
        override fun getByteArray(key: String) = CacheManager.getByteArray(key)
        override fun putFile(key: String, value: String, seconds: Int) = CacheManager.putFile(key, value, seconds)
        override fun getFile(key: String) = CacheManager.getFile(key)
    }

    fun call(method: String, args: List<Any?>, context: CoroutineContext = EmptyCoroutineContext,
             backend: Backend = Original): Any? {
        context.ensureActive()
        fun arity(min: Int, max: Int = min) = require(args.size in min..max) { "Invalid legacy cache overload" }
        fun key() = args[0] as? String ?: error("Legacy cache key required")
        fun seconds(): Int {
            val value = args.getOrNull(2) ?: return 0
            val number = value as? Number ?: error("Cache TTL seconds required")
            val integer = number.toInt()
            require(number.toDouble() == integer.toDouble()) { "Cache TTL must be signed Int seconds" }
            return integer
        }
        val result = when (method) {
            "cacheHost.put" -> { arity(2, 3); backend.put(key(), decode(args[1]), seconds()); null }
            "cacheHost.putMemory" -> { arity(2); backend.putMemory(key(), decode(args[1])); null }
            "cacheHost.get" -> {
                arity(1, 2); require(args.size == 1 || args[1] is Boolean)
                backend.get(key(), args.getOrNull(1) as? Boolean ?: false)
            }
            "cacheHost.delete" -> { arity(1); backend.delete(key()); null }
            "cacheHost.deleteMemory" -> { arity(1); backend.deleteMemory(key()); null }
            "cacheHost.getFromMemory" -> { arity(1); encode(backend.getFromMemory(key())) }
            "cacheHost.getInt" -> { arity(1); backend.getInt(key()) }
            "cacheHost.getLong" -> { arity(1); backend.getLong(key()) }
            "cacheHost.getDouble" -> { arity(1); backend.getDouble(key()) }
            "cacheHost.getFloat" -> { arity(1); backend.getFloat(key()) }
            "cacheHost.getByteArray" -> { arity(1); backend.getByteArray(key())?.let(::encode) }
            "cacheHost.putFile" -> { arity(2, 3); backend.putFile(key(), args[1] as? String ?: error("Cache file text required"), seconds()); null }
            "cacheHost.getFile" -> { arity(1); backend.getFile(key()) }
            else -> error("Unsupported legacy cache API")
        }
        context.ensureActive()
        if (result is Number) require(result.toDouble().isFinite()) { "Non-JSON cache number" }
        return result
    }

    private fun decode(value: Any?): Any {
        val envelope = value as? Map<*, *> ?: error("Cache value envelope required")
        require(envelope.keys == setOf("kind", "value")) { "Invalid cache value envelope" }
        val data = envelope["value"] ?: error("Cache value cannot be null")
        return when (envelope["kind"]) {
            "string" -> data as? String ?: error("Cache text required")
            "json" -> { BookSourceScriptBridge.jsonBindings(mapOf("value" to data)); data }
            "bytes" -> {
                val values = data as? List<*> ?: error("Cache byte list required")
                values.map {
                    val number = it as? Number ?: error("Cache integer byte required")
                    val byte = number.toInt()
                    require(number.toDouble() == byte.toDouble() && byte in -128..255) { "Cache byte out of range" }
                    byte.toByte()
                }.toByteArray()
            }
            else -> error("Unsupported cache value kind")
        }
    }
    private fun encode(value: Any?): Map<String, Any?>? {
        if (value == null) return null
        if (value is ByteArray) return mapOf("kind" to "bytes", "value" to value.map { it.toInt() })
        BookSourceScriptBridge.jsonBindings(mapOf("value" to value))
        return mapOf("kind" to "json", "value" to value)
    }
}
