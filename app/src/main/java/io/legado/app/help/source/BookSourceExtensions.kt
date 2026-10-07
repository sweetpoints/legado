package io.legado.app.help.source

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.model.ExploreInfoMapStore.exploreInfoMapList
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 采用md5作为key可以在分类修改后自动重新计算,不需要手动刷新 */
private val mutexMap by lazy { ConcurrentHashMap<String, Mutex>() }
private val exploreKindsMap by lazy { ConcurrentHashMap<String, List<ExploreKind>>() }
private val aCache by lazy { ACache.get("explore") }

private fun BookSource.getExploreKindsKey(): String {
    return MD5Utils.md5Encode(
        GSON.toJson(
            listOf(
                "flutter-v8-explore-v2",
                bookSourceUrl,
                exploreUrl,
                bookSourceComment,
                mainJs,
            )
        )
    )
}

private fun BookSourcePart.getExploreKindsKey(): String {
    return getBookSource()!!.getExploreKindsKey()
}

suspend fun BookSourcePart.exploreKinds(): List<ExploreKind> {
    return getBookSource()!!.exploreKinds()
}

suspend fun BookSource.exploreKinds(): List<ExploreKind> {
    val exploreKindsKey = getExploreKindsKey()
    exploreKindsMap[exploreKindsKey]?.let {
        return it
    }
    val exploreUrl = exploreUrl?.trim()
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val mutex = mutexMap.computeIfAbsent(bookSourceUrl) { Mutex() }
    mutex.withLock {
        exploreKindsMap[exploreKindsKey]?.let {
            return it
        }
        val kinds = arrayListOf<ExploreKind>()
        withContext(Dispatchers.IO) {
            kotlin
                .runCatching {
                    val ruleStr =
                        when {
                            exploreUrl.startsWith("@js:", true) ||
                                exploreUrl.startsWith("<js>", true) -> {
                                aCache.getAsString(exploreKindsKey)?.takeIf { it.isNotBlank() }
                                    ?: run {
                                        val script = requireNotNull(legacyExploreScript(exploreUrl))
                                        val info =
                                            exploreInfoMapList[bookSourceUrl]
                                                ?: InfoMap(bookSourceUrl).also {
                                                    exploreInfoMapList.put(bookSourceUrl, it)
                                                }
                                        exploreScriptResultText(
                                                evaluateExploreScript(
                                                    this@exploreKinds,
                                                    script,
                                                    info,
                                                )
                                            )
                                            .trim()
                                            .also { aCache.put(exploreKindsKey, it) }
                                    }
                            }
                            else -> exploreUrl
                        }
                    if (ruleStr.isJsonArray()) {
                        GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                            kinds.addAll(it)
                        }
                    } else {
                        ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                            val kindCfg = kindStr.split("::")
                            kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                        }
                    }
                }
                .onFailure {
                    currentCoroutineContext().ensureActive()
                    kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
                    it.printOnDebug()
                }
        }
        exploreKindsMap[exploreKindsKey] = kinds
        return kinds
    }
}

/** Legacy exports may keep a newline after the closing tag. */
internal fun legacyExploreScript(value: String): String? {
    val rule = value.trim()
    return when {
        rule.startsWith("@js:", true) -> rule.substring(4)
        rule.startsWith("<js>", true) -> {
            require(rule.endsWith("</js>", true)) { "发现菜单缺少 </js> 结束标记" }
            rule.substring(4, rule.length - 5)
        }
        else -> null
    }
}

/** Only JSON bindings cross the channel; old InfoMap Java methods fail visibly in V8. */
internal suspend fun evaluateExploreScript(
    source: BookSource,
    script: String,
    info: InfoMap,
): Any? {
    val snapshot = info.get().toMap()
    val wrapped =
        "(async()=>{const value=await eval(${GSON.toJson(script)});return {value:value===undefined?null:value,infoMap};})()"
    val result = DartSourceEngine.evaluate(source, wrapped, mapOf("infoMap" to snapshot))
    require(result is Map<*, *>) { "发现脚本未返回有效结果" }
    val updated = validateExploreInfoMap(result["infoMap"])
    if (snapshot != updated) {
        info.set(updated)
        info.saveNow()
    }
    return result["value"]
}

internal fun validateExploreInfoMap(value: Any?): Map<String, String> {
    require(value is Map<*, *>) { "infoMap 必须是字符串键和值组成的 JSON 对象" }
    return value.entries.associate { (key, item) ->
        require(key is String && item is String) { "infoMap 必须是字符串键和值组成的 JSON 对象" }
        key to item
    }
}

/** Channel results are JSON objects, not JVM toString representations. */
internal fun exploreScriptResultText(value: Any?): String =
    when (value) {
        null -> ""
        is String -> value
        is Map<*, *>,
        is List<*> -> GSON.toJson(value)
        else -> value.toString()
    }

suspend fun BookSourcePart.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

suspend fun BookSource.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

fun BookSource.exploreKindsJson(): String {
    val exploreKindsKey = getExploreKindsKey()
    return aCache.getAsString(exploreKindsKey)?.takeIf { it.isJsonArray() }
        ?: exploreUrl.takeIf { it.isJsonArray() }
        ?: ""
}

fun BookSource.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        BookSourceType.video -> BookType.video
        else -> BookType.text
    }
}
