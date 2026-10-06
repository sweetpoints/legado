package io.legado.app.model

import com.google.gson.reflect.TypeToken
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Source text cache only. JavaScript execution and lifetime belong to V8. */
object SharedJsScope {
    private val aCache by lazy { ACache.get(File(appCtx.cacheDir, "shareJs")) }
    private val libraryLock = Any()
    private val cryptoJs by lazy {
        appCtx.assets.open("scripts/cryptojs.min.js").bufferedReader().use { it.readText() }
    }
    private val cryptoPrelude by lazy {
        "(function(){\n$cryptoJs\nglobalThis.CryptoJS = CryptoJS;\n}).call(globalThis);\n" +
            "globalThis.CryptoJS.lib.WordArray.random = function(nBytes) { " +
            "var words=[]; for(var i=0;i<nBytes;i+=4) " +
            "words.push(__sourceHostSync('crypto.randomInt32', [])); " +
            "return globalThis.CryptoJS.lib.WordArray.create(words,nBytes); };\n"
    }

    fun resolveLibrary(jsLib: String?, coroutineContext: CoroutineContext? = null): String? {
        if (jsLib.isNullOrBlank()) return cryptoPrelude
        val references = parseJsLibMap(jsLib) ?: return cryptoPrelude + "\n;\n" + jsLib
        val library = references.values.filter { it.isAbsUrl() }.joinToString("\n;\n") { url ->
            synchronized(libraryLock) {
                val key = MD5Utils.md5Encode(url)
                aCache.getAsString(key) ?: runBlocking(
                    (coroutineContext ?: EmptyCoroutineContext) + Dispatchers.IO,
                ) {
                    okHttpClient.newCallStrResponse { url(url) }.body
                        ?.also { aCache.put(key, it) }
                        ?: throw NoStackTraceException("下载jsLib-$url 失败")
                }
            }
        }
        return cryptoPrelude + "\n;\n" + library
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        synchronized(libraryLock) {
            parseJsLibMap(jsLib)?.values?.filter { it.isAbsUrl() }?.forEach {
                aCache.remove(MD5Utils.md5Encode(it))
            }
        }
    }

    private fun parseJsLibMap(jsLib: String): Map<String, String>? {
        if (!jsLib.isJsonObject()) return null
        return GSON.fromJson(
            jsLib,
            TypeToken.getParameterized(Map::class.java, String::class.java, String::class.java).type,
        )
    }
}
