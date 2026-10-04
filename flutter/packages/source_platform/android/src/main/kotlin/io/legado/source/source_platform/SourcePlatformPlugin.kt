package io.legado.source.source_platform

import android.content.Context
import android.webkit.CookieManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.security.MessageDigest

/** Session/storage adapter; browser UI must be supplied by the host application. */
class SourcePlatformPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "legado/source_platform")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "read", "write" -> {
                    val sourceId = requireNotNull(call.argument<String>("sourceId"))
                    val key = requireNotNull(call.argument<String>("key"))
                    val namespace = MessageDigest.getInstance("SHA-256")
                        .digest(sourceId.toByteArray()).joinToString("") { "%02x".format(it) }
                    val storage = context.getSharedPreferences("source-$namespace", Context.MODE_PRIVATE)
                    if (call.method == "read") result.success(storage.getString(key, null))
                    else {
                        val value = call.argument<String>("value")
                        val editor = storage.edit()
                        if (value == null) editor.remove(key) else editor.putString(key, value)
                        editor.apply()
                        result.success(null)
                    }
                }
                "cookies" -> result.success(CookieManager.getInstance()
                    .getCookie(requireNotNull(call.argument<String>("url"))))
                "setCookie" -> CookieManager.getInstance().setCookie(
                    requireNotNull(call.argument<String>("url")),
                    requireNotNull(call.argument<String>("value"))) {
                        CookieManager.getInstance().flush()
                        result.success(null)
                    }
                "browser" -> result.error("CAPABILITY_UNAVAILABLE",
                    "Browser/login UI is not attached to this engine", null)
                else -> result.notImplemented()
            }
        } catch (error: IllegalArgumentException) {
            result.error("INVALID_ARGUMENT", error.message, null)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
