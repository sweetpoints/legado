package io.legado.app.help.source

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.model.ExploreInfoMapStore.exploreInfoMapList
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceHostCallbacks
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

/** The old parser extracted the tag body without requiring the tag to end the field. */
internal fun legacyExploreScript(value: String): String? {
    val rule = value.trim()
    return when {
        rule.startsWith("@js:", true) -> rule.substring(4)
        rule.startsWith("<js>", true) -> {
            val end = rule.lastIndexOf("</js>", ignoreCase = true)
            require(end >= 4) { "发现菜单缺少 </js> 结束标记" }
            rule.substring(4, end)
        }
        else -> null
    }
}

/** Discovery map operations stay bound to this task's original map and cache policy. */
internal suspend fun evaluateExploreScript(
    source: BookSource,
    script: String,
    info: InfoMap,
): Any? {
    val caller = currentCoroutineContext()[SourceHostCallbacks]
    return withContext(SourceHostCallbacks { method, arguments ->
        if (method.startsWith("exploreInfoMap.")) {
            dispatchExploreInfoMap(info, method.removePrefix("exploreInfoMap."), arguments)
        } else {
            check(caller != null) { "Unbound discovery callback: $method" }
            caller.call(method, arguments)
        }
    }) {
        DartSourceEngine.evaluate(source, exploreInfoMapScript(script))
    }
}

internal fun exploreInfoMapScript(script: String): String =
    """
    (async () => {
        const call = (name, args = []) => __sourceHostSync('exploreInfoMap.' + name, args);
        const methods = {
            get: (...args) => args.length === 0 ? mapView : call('get', args),
            put: (key, value) => call('put', [key, value]),
            remove: key => call('remove', [key]),
            putAll: value => { call('putAll', [value]); },
            containsKey: key => call('containsKey', [key]),
            containsValue: value => call('containsValue', [value]),
            size: () => call('size'),
            isEmpty: () => call('isEmpty'),
            clear: () => { call('clear'); },
            keySet: () => call('keys'),
            values: () => call('values'),
            entrySet: () => call('entries')
        };
        const ownerMethods = {
            set: value => { call('set', [value]); },
            save: (time = 0, need = true) => { call('save', [time, need]); },
            saveNow: () => { call('saveNow'); },
            getNeedSave: () => call('getNeedSave'),
            setNeedSave: value => { call('setNeedSave', [value]); },
            getSourceUrl: () => call('sourceUrl')
        };
        const makeView = owner => new Proxy(Object.create(null), {
            get: (_, key) => {
                if (key === 'toJSON') return () => call('get');
                if (typeof key === 'symbol') return undefined;
                if (owner && key === 'needSave') return call('getNeedSave');
                if (owner && key === 'sourceUrl') return call('sourceUrl');
                if (owner && Object.prototype.hasOwnProperty.call(ownerMethods, key)) return ownerMethods[key];
                if (Object.prototype.hasOwnProperty.call(methods, key)) return methods[key];
                const value = call('get', [key]);
                return value === null ? undefined : value;
            },
            set: (_, key, value) => {
                if (typeof key !== 'string') throw new TypeError('InfoMap keys must be strings');
                if (owner && key === 'needSave') call('setNeedSave', [value]);
                else if (owner && key === 'sourceUrl') throw new TypeError('InfoMap sourceUrl is read-only');
                else call('put', [key, value]);
                return true;
            },
            deleteProperty: (_, key) => { call('remove', [key]); return true; },
            ownKeys: () => call('keys'),
            getOwnPropertyDescriptor: (_, key) => call('containsKey', [key])
                ? {configurable:true, enumerable:true, writable:true, value:call('get', [key])} : undefined,
            has: (_, key) => typeof key === 'string' && call('containsKey', [key])
        });
        const mapView = makeView(false);
        const infoMap = makeView(true);
        const previousInfoMap = globalThis.infoMap;
        globalThis.infoMap = infoMap;
        try {
            return await eval(${GSON.toJson(script)});
        } finally {
            if (previousInfoMap === undefined) delete globalThis.infoMap;
            else globalThis.infoMap = previousInfoMap;
        }
    })()
    """.trimIndent()

internal fun exploreInfoMapSaveArguments(arguments: List<Any?>): Pair<Int, Boolean> {
    require(arguments.size <= 2) { "InfoMap.save accepts time and need" }
    val time = if (arguments.isEmpty()) 0 else {
        require(arguments[0] is Number) { "InfoMap.save time must be an integer" }
        val number = arguments[0] as Number
        val value = number.toDouble()
        require(value.isFinite() && value == number.toInt().toDouble()) { "InfoMap.save time must fit Int seconds" }
        number.toInt()
    }
    val need = if (arguments.size < 2) true else {
        require(arguments[1] is Boolean) { "InfoMap.save need must be a boolean" }
        arguments[1] as Boolean
    }
    return time to need
}

private fun dispatchExploreInfoMap(info: InfoMap, operation: String, arguments: List<Any?>): Any? {
    fun count(size: Int) { require(arguments.size == size) { "Invalid InfoMap.$operation argument count" } }
    fun text(index: Int): String {
        require(arguments[index] is String) { "InfoMap keys and values must be strings" }
        return arguments[index] as String
    }
    return when (operation) {
        "get" -> { require(arguments.size <= 1); if (arguments.isEmpty()) info.get().toMap() else info[text(0)] }
        "put" -> { count(2); info.put(text(0), text(1)) }
        "remove" -> { count(1); info.remove(text(0)) }
        "set" -> { count(1); info.set(validateExploreInfoMap(arguments[0])); null }
        "putAll" -> { count(1); info.putAll(validateExploreInfoMap(arguments[0])); null }
        "save" -> { val (time, need) = exploreInfoMapSaveArguments(arguments); info.save(time, need); null }
        "saveNow" -> { count(0); info.saveNow(); null }
        "getNeedSave" -> { count(0); info.needSave }
        "setNeedSave" -> { count(1); require(arguments[0] is Boolean); info.needSave = arguments[0] as Boolean; null }
        "containsKey" -> { count(1); info.containsKey(text(0)) }
        "containsValue" -> { count(1); info.containsValue(text(0)) }
        "size" -> { count(0); info.size }
        "isEmpty" -> { count(0); info.isEmpty() }
        "clear" -> { count(0); info.clear(); null }
        "keys" -> { count(0); info.keys.toList() }
        "values" -> { count(0); info.values.toList() }
        "entries" -> { count(0); info.entries.map { mapOf("key" to it.key, "value" to it.value) } }
        "sourceUrl" -> { count(0); info.sourceUrl }
        else -> error("Unsupported InfoMap operation: $operation")
    }
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
