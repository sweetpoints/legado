package io.legado.app.model.jsSource

import com.google.gson.JsonObject
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.login.LoginUiV2
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.isMainThread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.Parser
import org.htmlunit.corejs.javascript.ast.FunctionCall
import org.htmlunit.corejs.javascript.ast.FunctionNode
import org.htmlunit.corejs.javascript.ast.Name
import org.htmlunit.corejs.javascript.ast.NumberLiteral
import org.htmlunit.corejs.javascript.ast.ObjectLiteral
import org.htmlunit.corejs.javascript.ast.ObjectProperty
import org.htmlunit.corejs.javascript.ast.StringLiteral
import org.htmlunit.corejs.javascript.ast.VariableInitializer

object JsSourceConfig {

    private const val CONFIG_PROPERTY = "config"
    private const val LEGACY_CONFIG_PROPERTY = "source"
    private val configurationJson by lazy { GSON.newBuilder().serializeNulls().create() }
    private val reviewFunctionNames = setOf("getReviewSummary", "getReviewDetail")
    private val reviewReplyFunctionNames = setOf("getReviewReplies")

    val requiredFunctions = listOf("search", "getChapters", "getContent")
    private val fileSourceRequiredFunctions = listOf("search", "getBookInfo")

    private val strippedKeys =
        listOf(
            "mainJs",
            "ruleSearch",
            "ruleExplore",
            "ruleBookInfo",
            "ruleToc",
            "ruleContent",
            "ruleReview",
        )

    fun extract(text: String, coroutineContext: CoroutineContext? = null): BookSource {
        if (isMainThread) throw NoStackTraceException("JS书源配置必须在后台线程通过 Flutter/V8 解析")
        val value =
            try {
                runBlocking((coroutineContext ?: EmptyCoroutineContext) + Dispatchers.IO) {
                    DartSourceEngine.evaluateConfiguration(configurationScript(text))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw NoStackTraceException("JS源脚本执行失败: ${error.message}")
            }
        if (value !is Map<*, *> || value.keys.any { it !is String }) {
            throw NoStackTraceException("JS源配置提取结果不是合法对象")
        }
        return materializeRuntimeConfig(
            value.entries.associate { it.key as String to it.value },
            text,
        )
    }

    fun declaresReviewFunctions(text: String): Boolean {
        return declaresTopLevelFunctions(text, reviewFunctionNames)
    }

    fun declaresReviewRepliesFunction(text: String): Boolean {
        return declaresTopLevelFunctions(text, reviewReplyFunctionNames)
    }

    private fun declaresTopLevelFunctions(text: String, names: Set<String>): Boolean {
        val root = runCatching { Parser().parse(text, null, 1) }.getOrNull()
            ?: return JsSourceDeclarations.declares(text, names)
        val declared = hashSetOf<String>()
        root.visit { node ->
            when (node) {
                is FunctionNode -> if (node.enclosingFunction == null && node.name in names) {
                    declared.add(node.name)
                }
                is VariableInitializer -> if (node.enclosingFunction == null && node.initializer is FunctionNode) {
                    (node.target as? Name)?.identifier?.takeIf { it in names }?.let(declared::add)
                }
            }
            declared.size < names.size
        }
        return declared.containsAll(names)
    }

    internal fun configurationScript(text: String): String {
        val names =
            (requiredFunctions +
                    fileSourceRequiredFunctions +
                    listOf(
                        "explore",
                        "loginUi",
                        "loginAction",
                        "login",
                        "getReviewSummary",
                        "getReviewDetail",
                        "getReviewReplies",
                        "getContentBatch",
                    ))
                .distinct()
        val types = names.joinToString(",") { "${GSON.toJson(it)}:typeof $it" }
        return """
            (async(__sourceEngineApi)=>{
            $text
            return {
              config:typeof config==='undefined'?null:config,
              legacyConfig:typeof source==='undefined'||source===__sourceEngineApi?null:source,
              functions:{$types}
            };
            })(typeof source==='undefined'?undefined:source)
        """
            .trimIndent()
    }

    internal fun materializeRuntimeConfig(value: Map<String, Any?>, text: String): BookSource {
        val functions =
            value["functions"] as? Map<*, *> ?: throw NoStackTraceException("JS源函数信息无法解析")
        fun type(name: String): String = functions[name] as? String ?: "undefined"
        val (configName, config) = findConfig(value)
        val json = if (config is String) config else configurationJson.toJson(config)
        val jsonObject =
            runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
                ?: throw NoStackTraceException("$configName 配置对象不是合法对象")
        val maxBatchSize = readMaxBatchSize(jsonObject, configName)
        strippedKeys.forEach(jsonObject::remove)
        normalizeExploreUrl(jsonObject)
        normalizeLoginUi(jsonObject)
        val source =
            runCatching { GSON.fromJson(jsonObject, BookSource::class.java) }.getOrNull()
                ?: throw NoStackTraceException("$configName 配置对象字段类型不符")
        if (source.bookSourceUrl.isBlank()) {
            throw NoStackTraceException("JS源 $configName.bookSourceUrl 不能为空")
        }
        if (source.bookSourceName.isBlank()) {
            throw NoStackTraceException("JS源 $configName.bookSourceName 不能为空")
        }
        val required =
            if (source.bookSourceType == BookSourceType.file) {
                fileSourceRequiredFunctions
            } else {
                requiredFunctions
            }
        required.forEach { name ->
            if (type(name) != "function") {
                throw NoStackTraceException("JS源缺少必备函数 $name")
            }
        }
        if (!source.exploreUrl.isNullOrBlank() && type("explore") != "function") {
            throw NoStackTraceException("JS源声明了 exploreUrl,缺少配对的 explore 函数")
        }
        val loginUiFunction = type("loginUi")
        if (loginUiFunction == "function") {
            if (!source.loginUi.isNullOrBlank()) {
                throw NoStackTraceException("loginUi 函数与 config.loginUi 数据只能二选一")
            }
            if (type("loginAction") != "function") {
                throw NoStackTraceException("JS源声明了 loginUi 函数,缺少配对的 loginAction 函数")
            }
            source.loginUi = LoginUiV2.MARKER
        } else if (!source.loginUi.isNullOrBlank() && type("login") != "function") {
            throw NoStackTraceException("JS源声明了 loginUi,缺少配对的 login 函数")
        }
        val reviewSummary = type("getReviewSummary")
        val reviewDetail = type("getReviewDetail")
        val reviewReplies = type("getReviewReplies")
        val declaresReviewSummary = reviewSummary != "undefined"
        val declaresReviewDetail = reviewDetail != "undefined"
        val declaresReviewReplies = reviewReplies != "undefined"
        if (declaresReviewSummary && reviewSummary != "function") {
            throw NoStackTraceException("JS源 getReviewSummary 必须是函数")
        }
        if (declaresReviewDetail && reviewDetail != "function") {
            throw NoStackTraceException("JS源 getReviewDetail 必须是函数")
        }
        if (declaresReviewReplies && reviewReplies != "function") {
            throw NoStackTraceException("JS源 getReviewReplies 必须是函数")
        }
        if (declaresReviewSummary && !declaresReviewDetail) {
            throw NoStackTraceException("JS源声明了 getReviewSummary,缺少配对的 getReviewDetail 函数")
        }
        if (declaresReviewDetail && !declaresReviewSummary) {
            throw NoStackTraceException("JS源声明了 getReviewDetail,缺少配对的 getReviewSummary 函数")
        }
        if (declaresReviewReplies && (!declaresReviewSummary || !declaresReviewDetail)) {
            throw NoStackTraceException(
                "JS源声明了 getReviewReplies,缺少配对的 getReviewSummary/getReviewDetail 函数"
            )
        }
        source.mainJs = text
        if (declaresReviewSummary) {
            JsSourceReview.rememberReviewCapability(source, enabled = true)
        }
        if (maxBatchSize != null) {
            if (type("getContentBatch") != "function") {
                throw NoStackTraceException("JS源声明了 maxBatchSize,缺少配对的 getContentBatch 函数")
            }
            // ruleContent 已被剥离,批量数量单独回填,供批量下载调度使用
            source.ruleContent = ContentRule(maxBatchSize = maxBatchSize)
        } else if (type("getContentBatch") == "function") {
            throw NoStackTraceException("JS源声明了 getContentBatch,缺少配对的 config.maxBatchSize")
        }
        if (declaresReviewReplies) {
            JsSourceReview.rememberReviewRepliesCapability(source, enabled = true)
        }
        return source
    }

    /** 读取 config.maxBatchSize:批量正文每批的章节数,缺省表示不启用批量 */
    private fun readMaxBatchSize(jsonObject: JsonObject, configName: String): Int? {
        val element = jsonObject.get("maxBatchSize") ?: return null
        val size =
            runCatching {
                require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
                element.asBigDecimal.intValueExact()
            }
                .getOrNull() ?: throw NoStackTraceException("$configName.maxBatchSize 必须是整数")
        if (size <= 1) {
            throw NoStackTraceException("$configName.maxBatchSize 必须大于1,不启用批量时删除该字段")
        }
        return size.coerceAtMost(BookSource.MAX_CONTENT_BATCH_SIZE)
    }

    private fun findConfig(value: Map<String, Any?>): Pair<String, Any> {
        val config = value["config"]
        val legacyConfig = value["legacyConfig"]
        if (config != null && (legacyConfig == null || isCompleteConfig(config))) {
            return CONFIG_PROPERTY to config
        }
        if (legacyConfig != null) return LEGACY_CONFIG_PROPERTY to legacyConfig
        throw NoStackTraceException("JS源缺少顶层 config 配置对象（兼容旧版 source）")
    }

    private fun isCompleteConfig(value: Any): Boolean {
        val json = if (value is String) value else configurationJson.toJson(value)
        val jsonObject =
            runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return false
        return runCatching {
                jsonObject.get("bookSourceUrl")?.asString?.isNotBlank() == true &&
                    jsonObject.get("bookSourceName")?.asString?.isNotBlank() == true
            }
            .getOrDefault(false)
    }

    private fun normalizeExploreUrl(jsonObject: JsonObject) {
        val element = jsonObject.get("exploreUrl") ?: return
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        if (array.size() == 0) {
            jsonObject.remove("exploreUrl")
            return
        }
        array.forEachIndexed { index, item ->
            val title = runCatching { item.asJsonObject.get("title")?.asString }.getOrNull()
            if (title.isNullOrBlank()) {
                throw NoStackTraceException("exploreUrl 第 ${index + 1} 项缺少 title")
            }
        }
        jsonObject.addProperty("exploreUrl", GSON.toJson(array))
    }

    private fun normalizeLoginUi(jsonObject: JsonObject) {
        val element = jsonObject.get("loginUi") ?: return
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            if (element.asString.filterNot { it.isWhitespace() } == "[]") {
                jsonObject.remove("loginUi")
            }
            return
        }
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        if (array.size() == 0) {
            jsonObject.remove("loginUi")
            return
        }
        array.forEachIndexed { index, item ->
            val name = runCatching { item.asJsonObject.get("name")?.asString }.getOrNull()
            if (name.isNullOrBlank()) {
                throw NoStackTraceException("loginUi 第 ${index + 1} 项缺少 name")
            }
        }
        jsonObject.addProperty("loginUi", GSON.toJson(array))
    }

    fun stampLastUpdateTime(text: String, stamp: Long): String? {
        val ranges = runCatching {
            mutableListOf<IntRange>().apply {
                Parser().parse(text, null, 1).visit { node ->
                    val initializer = node as? VariableInitializer ?: return@visit true
                    val name = (initializer.target as? Name)?.identifier
                    val config = initializer.initializer as? ObjectLiteral
                    if (
                        initializer.enclosingFunction != null ||
                            name != CONFIG_PROPERTY && name != LEGACY_CONFIG_PROPERTY ||
                            config == null
                    ) {
                        return@visit true
                    }
                    config.elements.filterIsInstance<ObjectProperty>().forEach { property ->
                        val key =
                            when (val nodeKey = property.key) {
                                is Name -> nodeKey.identifier
                                is StringLiteral -> nodeKey.value
                                else -> null
                            }
                        val value = property.value
                        val isSupportedValue =
                            value is NumberLiteral ||
                                value is FunctionCall &&
                                    value.arguments.isEmpty() &&
                                    value.target.toSource() == "Date.now"
                        if (key == "lastUpdateTime" && isSupportedValue) {
                            add(value.absolutePosition until value.absolutePosition + value.length)
                        }
                    }
                    true
                }
            }
        }
            .getOrNull() ?: JsSourceDeclarations.timestampRanges(text)
        if (ranges.isEmpty()) return null
        return ranges
            .sortedByDescending { it.first }
            .fold(text) { script, range ->
                script.replaceRange(range, stamp.toString())
            }
    }
}
