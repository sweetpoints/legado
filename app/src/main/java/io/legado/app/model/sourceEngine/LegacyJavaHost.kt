package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import kotlinx.coroutines.ensureActive

/** Explicit JSON host boundary. Never resolves arbitrary Java names or objects. */
object LegacyJavaHost {
    val methods = setOf(
        "javaHost.log", "javaHost.logType", "javaHost.toast", "javaHost.longToast",
        "javaHost.timeFormat", "javaHost.timeFormatUTC", "javaHost.t2s", "javaHost.s2t",
        "javaHost.getCookie",
    )

    fun call(extensions: JsExtensions, method: String, args: List<Any?>): Any? {
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
}
