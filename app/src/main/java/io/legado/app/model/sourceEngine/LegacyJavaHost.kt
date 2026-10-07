package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import cn.hutool.crypto.symmetric.SymmetricCrypto
import java.nio.charset.Charset
import kotlinx.coroutines.ensureActive

/** Explicit JSON host boundary. Never resolves arbitrary Java names or objects. */
object LegacyJavaHost {
    val methods = setOf(
        "javaHost.log", "javaHost.logType", "javaHost.toast", "javaHost.longToast",
        "javaHost.timeFormat", "javaHost.timeFormatUTC", "javaHost.t2s", "javaHost.s2t",
        "javaHost.getCookie",
        "javaHost.cryptoCreate", "javaHost.cryptoCall", "javaHost.aesBase64DecodeToString", "javaHost.desEncodeToBase64String", "javaHost.getWebViewUA",
        "javaHost.HMacHex", "javaHost.HMacBase64", "javaHost.androidId", "javaHost.randomUUID", "javaHost.toNumChapter",
    )

    fun call(extensions: JsExtensions, method: String, args: List<Any?>, ownerId: String = ""): Any? {
        extensions.getSourceNavigationContext().ensureActive()
        fun arity(min: Int, max: Int = min) = require(args.size in min..max) { "Invalid legacy overload" }
        fun text(index: Int): String = args[index] as? String ?: error("Legacy string argument required")
        fun integer(index: Int): Long {
            val number = args[index] as? Number ?: error("Legacy integer argument required")
            if (number is Byte || number is Short || number is Int || number is Long) return number.toLong()
            val value = number.toDouble()
            require(value.isFinite() && value >= Long.MIN_VALUE.toDouble() && value < 9223372036854775808.0 && value == kotlin.math.floor(value)) {
                "Legacy integer argument required"
            }
            return value.toLong()
        }
        return when (method) {
            "javaHost.cryptoCreate" -> {
                arity(2, 3)
                val transformation = text(0)
                require(!transformation.startsWith("PBE", ignoreCase = true)) {
                    "legacy.unsupported_crypto_parameter_snapshot"
                }
                val key = args[1]; val iv = args.getOrNull(2)
                val crypto = if (key is String) {
                    require(iv == null || iv is String) { "Legacy string IV required" }
                    extensions.createSymmetricCrypto(transformation, key, iv as String?)
                } else extensions.createSymmetricCrypto(transformation, bytes(key), bytes(iv))
                val explicitIv = (if (iv is String) iv.toByteArray() else bytes(iv))?.takeIf { it.isNotEmpty() }
                mapOf("__legacyCryptoState" to mapOf(
                    "schemaVersion" to 1, "ownerId" to ownerId,
                    "transformation" to transformation,
                    "key" to (crypto.secretKey.encoded ?: error("Legacy exportable key required")).map { it.toInt() },
                    "iv" to explicitIv?.map { it.toInt() },
                ))
            }
            "javaHost.cryptoCall" -> {
                arity(3)
                val state = args[0] as? Map<*, *> ?: error("Legacy crypto state required")
                require((state["schemaVersion"] as? Number)?.toDouble() == 1.0 && state["ownerId"] == ownerId) { "Invalid legacy crypto state owner" }
                val transformation = state["transformation"] as? String ?: error("Legacy transformation required")
                require(!transformation.startsWith("PBE", ignoreCase = true)) { "legacy.unsupported_crypto_parameter_snapshot" }
                val key = bytes(state["key"]) ?: error("Legacy key bytes required")
                val iv = bytes(state["iv"])
                val crypto = extensions.createSymmetricCrypto(transformation, key, null)
                if (iv != null) crypto.setIv(iv)
                val operation = text(1)
                val values = args[2] as? List<*> ?: error("Legacy crypto argument list required")
                val result = cryptoCall(crypto, operation, values)
                // Hutool reinitializes each operation from configured params; a
                // provider-generated IV is not promoted to configured setIv state.
                val updatedIv = if (operation == "setIv") bytes(values.single()) else iv
                mapOf("value" to result, "state" to mapOf(
                    "schemaVersion" to 1, "ownerId" to ownerId, "transformation" to transformation,
                    "key" to key.map { it.toInt() }, "iv" to updatedIv?.map { it.toInt() },
                ))
            }
            "javaHost.aesBase64DecodeToString" -> { arity(4); extensions.aesBase64DecodeToString(text(0), text(1), text(2), text(3)) }
            "javaHost.desEncodeToBase64String" -> { arity(4); extensions.desEncodeToBase64String(text(0), text(1), text(2), text(3)) }
            "javaHost.getWebViewUA" -> { arity(0); extensions.getWebViewUA() }

            "javaHost.HMacHex" -> { arity(3); extensions.HMacHex(text(0), text(1), text(2)) }
            "javaHost.HMacBase64" -> { arity(3); extensions.HMacBase64(text(0), text(1), text(2)) }
            "javaHost.androidId" -> { arity(0); extensions.androidId() }
            "javaHost.randomUUID" -> { arity(0); extensions.randomUUID() }
            "javaHost.toNumChapter" -> {
                arity(1)
                require(args[0] == null || args[0] is String) { "Legacy chapter must be string or null" }
                extensions.toNumChapter(args[0] as String?)
            }

            "javaHost.log" -> { arity(1); extensions.log(args[0]) }
            "javaHost.logType" -> { arity(1); extensions.logType(args[0]); null }
            "javaHost.toast" -> { arity(1); extensions.toast(args[0]); null }
            "javaHost.longToast" -> { arity(1); extensions.longToast(args[0]); null }
            "javaHost.t2s" -> { arity(1); extensions.t2s(text(0)) }
            "javaHost.s2t" -> { arity(1); extensions.s2t(text(0)) }
            "javaHost.timeFormat" -> { arity(1); extensions.timeFormat(integer(0)) }
            "javaHost.timeFormatUTC" -> {
                arity(3)
                val offset = integer(2)
                require(offset in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Legacy UTC offset out of range" }
                extensions.timeFormatUTC(integer(0), text(1), offset.toInt())
            }
            "javaHost.getCookie" -> {
                arity(1, 2)
                if (args.size == 1) extensions.getCookie(text(0))
                else {
                    require(args[1] == null || args[1] is String) { "Legacy cookie key must be string or null" }
                    extensions.getCookie(text(0), args[1] as String?)
                }
            }
            else -> error("Unsupported legacy host API")
        }
    }
    private fun bytes(value: Any?): ByteArray? {
        if (value == null) return null
        val values = value as? List<*> ?: error("Legacy crypto byte list required")
        return values.map {
            val number = it as? Number ?: error("Legacy crypto byte required")
            val byte = number.toInt()
            require(number.toDouble() == byte.toDouble() && byte in -128..255) { "Legacy crypto byte out of range" }
            byte.toByte()
        }.toByteArray()
    }
    private fun cryptoCall(crypto: SymmetricCrypto, operation: String, args: List<*>): Any? {
        require(args.size in 1..2) { "Legacy crypto overload required" }
        val data = args[0]
        if (operation == "setIv") {
            require(args.size == 1) { "Legacy setIv overload required" }
            crypto.setIv(bytes(data) ?: error("Legacy IV bytes required")); return null
        }
        val charset = if (args.size == 2) Charset.forName(args[1] as? String ?: error("Legacy charset required")) else Charsets.UTF_8
        return when (operation) {
            "encrypt" -> (if (data is String) crypto.encrypt(data, charset) else {
                require(args.size == 1); crypto.encrypt(bytes(data) ?: error("Legacy data bytes required"))
            }).map { it.toInt() }
            "encryptHex" -> if (data is String) crypto.encryptHex(data, charset) else {
                require(args.size == 1); crypto.encryptHex(bytes(data) ?: error("Legacy data bytes required"))
            }
            "encryptBase64" -> if (data is String) crypto.encryptBase64(data, charset) else {
                require(args.size == 1); crypto.encryptBase64(bytes(data) ?: error("Legacy data bytes required"))
            }
            "decrypt" -> {
                require(args.size == 1)
                (if (data is String) crypto.decrypt(data) else crypto.decrypt(bytes(data) ?: error("Legacy data bytes required"))).map { it.toInt() }
            }
            "decryptStr" -> if (data is String) crypto.decryptStr(data, charset) else crypto.decryptStr(bytes(data) ?: error("Legacy data bytes required"), charset)
            else -> error("Unsupported legacy crypto operation")
        }
    }

}
