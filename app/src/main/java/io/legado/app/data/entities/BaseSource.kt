package io.legado.app.data.entities

import android.webkit.JavascriptInterface
import cn.hutool.crypto.symmetric.AES
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.updateConcurrentRate
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope
import io.legado.app.model.SharedJsScope.remove
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.login.LoginUiV2
import io.legado.app.model.sourceEngine.BookSourceScriptBridge
import io.legado.app.model.sourceEngine.V8ScriptExecutor
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.SourceHostCallbacks
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.has
import io.legado.app.utils.isMainThread
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language

internal object LoginInfoMapInitialization {
    private val initializingSourceKeys = ThreadLocal<MutableSet<String>>()

    fun <T> run(sourceKey: String, onReentry: () -> T, block: () -> T): T {
        val activeKeys = initializingSourceKeys.get()
            ?: mutableSetOf<String>().also(initializingSourceKeys::set)
        if (!activeKeys.add(sourceKey)) {
            return onReentry()
        }
        return try {
            block()
        } finally {
            activeKeys.remove(sourceKey)
            if (activeKeys.isEmpty()) {
                initializingSourceKeys.remove()
            }
        }
    }
}

internal fun BaseSource.getStoredLoginInfoMap(): MutableMap<String, String>? {
    val json = getLoginInfo() ?: return null
    return GSON.fromJsonObject<MutableMap<String, String>>(json).getOrNull()
        ?: mutableMapOf()
}

/**
 * 可在js里调用,source.xxx()
 */
@Suppress("unused")
interface BaseSource : JsExtensions {
    /**
     * 并发率
     */
    var concurrentRate: String?

    /**
     * 登录地址
     */
    var loginUrl: String?

    /**
     * 登录UI
     */
    var loginUi: String?

    /**
     * 请求头
     */
    var header: String?

    /**
     * 启用cookieJar
     */
    var enabledCookieJar: Boolean?

    /**
     * js库
     */
    var jsLib: String?

    override fun getTag(): String

    fun getKey(): String

    override fun getSource(): BaseSource? {
        return this
    }

    private fun extractInlineJs(rule: String?): String? {
        val text = rule?.trim().orEmpty()
        if (text.isBlank()) return null
        val matcher = JS_PATTERN.matcher(text)
        if (!matcher.matches()) return null
        return (matcher.group(1) ?: matcher.group(2))?.trim()
    }

    fun getLoginUiJs(): String? {
        return extractInlineJs(loginUi)
    }

    fun isLoginUiV2(): Boolean {
        return LoginUiV2.isV2(loginUi)
    }

    fun evalLoginUiV2(
        stateJson: String,
        book: Book? = null,
        chapter: BookChapter? = null,
    ): String? {
        val loginJs = getLoginJs()
            ?: throw NoStackTraceException("登录UI v2 缺少 loginUi/loginAction 脚本")
        val result = evalJS(
            "$loginJs\nloginUi(JSON.parse(String(__loginState)))"
        ) {
            put("__loginState", stateJson)
            put("book", book)
            put("chapter", chapter)
        }
        return JsSourceEngine.normalizeJsResult(result)
    }

    fun evalLoginActionV2(
        action: String,
        stateJson: String,
        formJson: String,
        book: Book? = null,
        chapter: BookChapter? = null,
    ): String? {
        val loginJs = getLoginJs()
            ?: throw NoStackTraceException("登录UI v2 缺少 loginUi/loginAction 脚本")
        val result = evalJS(
            "$loginJs\n" +
                "loginAction(String(__loginAction), JSON.parse(String(__loginState)), " +
                "JSON.parse(String(__loginForm)))"
        ) {
            put("__loginAction", action)
            put("__loginState", stateJson)
            put("__loginForm", formJson)
            put("book", book)
            put("chapter", chapter)
        }
        return JsSourceEngine.normalizeJsResult(result)
    }

    fun getLoginJs(): String? {
        val loginRule = loginUrl?.trim()
        if (loginRule.isNullOrBlank()) return null
        return extractInlineJs(loginRule) ?: loginRule
    }

    fun hasLoginForm(): Boolean {
        val form = loginUi?.trim().orEmpty()
        return form.isNotEmpty() && form.filterNot { it.isWhitespace() } != "[]"
    }

    fun hasLogin(): Boolean {
        return !loginUrl.isNullOrBlank() || hasLoginForm()
    }

    /**
     * 调用login函数 实现登录请求
     */
    @JavascriptInterface
    fun login() {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            @Language("js")
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    /**
     * 解析header规则
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = HashMap<String, String>().apply {
        header?.let {
            try {
                val json = extractInlineJs(it)?.let { js ->
                    JsSourceEngine.normalizeJsResult(evalJS(js)).orEmpty()
                } ?: it
                GSON.fromJsonObject<Map<String, String>>(json).getOrNull()?.let { map ->
                    putAll(map)
                }
            } catch (e: Exception) {
                AppLog.put("执行请求头规则出错\n$e", e)
            }
        }
        if (!has(AppConst.UA_NAME, true)) {
            put(AppConst.UA_NAME, AppConfig.userAgent)
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let {
                putAll(it)
            }
        }
    }

    /**
     * 获取用于登录的头部信息
     */
    @JavascriptInterface
    fun getLoginHeader(): String? {
        return CacheManager.get("loginHeader_${getKey()}")
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
    }

    /**
     * 保存登录头部信息,map格式,访问时自动添加
     */
    fun putLoginHeader(header: String) {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let {
            CookieStore.replaceCookie(getKey(), it)
        }
        CacheManager.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        CacheManager.delete("loginHeader_${getKey()}")
        CookieStore.removeCookie(getKey())
    }

    /**
     * 获取用户信息,可以用来登录
     * 用户信息采用aes加密存储
     */
    @JavascriptInterface
    fun getLoginInfo(): String? {
        try {
            val key = AppConst.androidId.encodeToByteArray(0, 16)
            val cache = CacheManager.get("userInfo_${getKey()}") ?: return null
            return AES(key).decryptStr(cache)
        } catch (e: Exception) {
            AppLog.put("获取登陆信息出错", e)
            return null
        }
    }

    private fun configureEvalBindings(): MutableMap<String, Any?>.() -> Unit = {
        put("result", mutableMapOf<String, String>())
        put("book", null)
        put("chapter", null)
    }

    fun getLoginInfoMap(): MutableMap<String, String> {
        getStoredLoginInfoMap()?.let { return it }
        if (isLoginUiV2()) return mutableMapOf()
        val loginUiRule = loginUi?.trim().takeUnless { it.isNullOrBlank() } ?: return mutableMapOf()
        return LoginInfoMapInitialization.run(
            sourceKey = getKey(),
            onReentry = { mutableMapOf<String, String>() },
        ) {
            // Dynamic login UI scripts can read login info while their defaults are being derived.
            val loginUiJs = extractInlineJs(loginUiRule)
            val loginUiJson = if (loginUiJs != null) {
                JsSourceEngine.normalizeJsResult(
                    evalJS(
                        "${getLoginJs() ?: ""}\n$loginUiJs",
                        configureEvalBindings()
                    )
                ).orEmpty()
            } else {
                loginUiRule
            }
            val loginInfo = GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()
                ?.filter { it.type != "button" }
                ?.associate { it.name to (it.default ?: "") }
                ?.takeIf { it.isNotEmpty() }?.also {
                    putLoginInfo(GSON.toJson(it))
                }
            loginInfo?.toMutableMap() ?: mutableMapOf()
        }
    }

    /**
     * 保存用户信息,aes加密
     */
    @JavascriptInterface
    fun putLoginInfo(info: String): Boolean {
        return try {
            val key = (AppConst.androidId).encodeToByteArray(0, 16)
            val encodeStr = SymmetricCryptoAndroid("AES", key).encryptBase64(info)
            CacheManager.put("userInfo_${getKey()}", encodeStr)
            true
        } catch (e: Exception) {
            AppLog.put("保存登陆信息出错", e)
            false
        }
    }

    @JavascriptInterface
    fun removeLoginInfo() {
        CacheManager.delete("userInfo_${getKey()}")
    }

    /**
     * 设置自定义变量
     * @param variable 变量内容
     */
    fun setVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    /**
     * 设置自定义变量
     * 新,统一为put名称存变量
     */
    @JavascriptInterface
    fun putVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    /**
     * 获取自定义变量
     */
    @JavascriptInterface
    fun getVariable(): String {
        return CacheManager.get("sourceVariable_${getKey()}") ?: ""
    }

    /**
     * 保存数据
     */
    @JavascriptInterface
    fun put(key: String, value: String): String {
        CacheManager.put("v_${getKey()}_${key}", value)
        return value
    }

    /**
     * 获取保存的数据
     */
    @JavascriptInterface
    fun get(key: String): String {
        return CacheManager.get("v_${getKey()}_${key}") ?: ""
    }

    /**
     * 刷新发现
     */
    fun refreshExplore() {
        if (isMainThread) {
            error("refreshExplore must be called on a background thread")
        }
        runBlocking {
            if (this@BaseSource is BookSource) {
                this@BaseSource.clearExploreKindsCache()
            }
        }
    }

    /**
     * 刷新JSLib
     */
    fun refreshJSLib() {
        if (isMainThread) {
            error("refreshJSLib must be called on a background thread")
        }
        runBlocking {
            remove(jsLib)
            DartSourceEngine.clearSourceState(this@BaseSource)
        }
    }

    /**
     * 设置并发率
     */
    fun putConcurrent(value: String) {
        updateConcurrentRate(getKey(),value)
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    fun evalJS(jsStr: String, bindingsConfig: MutableMap<String, Any?>.() -> Unit = {}): Any? {
        val original = getSource() ?: this
        val values = BookSourceScriptBridge.bindings(bindingsConfig).toMutableMap()
        values["__baseSourceScript"] = jsStr
        val context = getSourceNavigationContext()
        val caller = context[SourceHostCallbacks]
        val callbacks = SourceHostCallbacks { method, args ->
            fun text(index: Int): String = args.getOrNull(index)?.toString() ?: ""
            when (method) {
                "analyze.get" -> original.get(text(0))
                "analyze.put" -> original.put(text(0), text(1))
                "sourceState.getLoginInfo" -> original.getLoginInfo()
                "sourceState.putLoginInfo" -> original.putLoginInfo(text(0))
                "sourceState.getLoginHeader" -> original.getLoginHeader()
                "sourceState.putLoginHeader" -> { original.putLoginHeader(text(0)); null }
                "sourceState.getVariable" -> original.getVariable()
                "sourceState.putVariable" -> { original.putVariable(args.firstOrNull()?.toString()); null }
                "sourceState.removeLoginInfo" -> { original.removeLoginInfo(); null }
                else -> {
                    check(caller != null) { "Unbound source callback: $method" }
                    caller.call(method, args)
                }
            }
        }
        val script = """
            (async function() {
                var nativeJava = globalThis.java;
                var java = new Proxy(Object.create(null), {
                    get: (_, name) => ['get','put'].includes(String(name))
                        ? (...args) => name === 'get' && args.length !== 1
                            ? nativeJava[name](...args)
                            : __sourceHostSync('analyze.' + String(name), args)
                        : ['getLoginInfo','putLoginInfo','getLoginHeader','putLoginHeader','getVariable','putVariable','removeLoginInfo'].includes(String(name))
                            ? (...args) => __sourceHostSync('sourceState.' + String(name), args)
                            : nativeJava && nativeJava[name]
                });
                return await eval(__baseSourceScript);
            }).call(globalThis)
        """.trimIndent()
        return V8ScriptExecutor.evaluateBlocking(
            script, values, context + callbacks, source = this,
        )
    }
}
