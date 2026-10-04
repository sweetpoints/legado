package io.legado.app.help.http

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AndroidRuntimeException
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.BuildConfig
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.withSourceNavigationContext
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewRequestConfig
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.model.Debug
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext

/**
 * 后台webView
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: HashMap<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private var delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pooledWebView: PooledWebView? = null
    private var requestWebViewClient: WebViewClient? = null
    private val requestStartedAt = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L

    private fun traceStage(stage: String) {
        if (BuildConfig.DEBUG) {
            // Only control-flow metadata: source URLs, rules, headers and results are private.
            Log.d("BackstageWebViewStage", "request=${System.identityHashCode(this)} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt} stage=$stage")
        }
    }

    suspend fun getStrResponse(): StrResponse = try {
        withTimeout(timeout ?: 60000L) {
            traceStage("await-response")
            suspendCancellableCoroutine { block ->
                block.invokeOnCancellation {
                    traceStage("cancelled")
                    runOnUI {
                        if (BuildConfig.DEBUG) {
                            pooledWebView?.let { lease ->
                                val view = lease.realWebView
                                traceStage("cancel-state lease=${System.identityHashCode(lease)} " +
                                    "generation=${lease.recycleGeneration} inUse=${lease.isInUse} " +
                                    "clientMatches=${view.webViewClient === requestWebViewClient} " +
                                    "attached=${view.isAttachedToWindow} visibility=${view.visibility} " +
                                    "progress=${view.progress} jsEnabled=${view.settings.javaScriptEnabled}")
                            }
                        }
                        destroy()
                    }
                }
                callback = object : Callback() {
                    override fun onResult(response: StrResponse) {
                        traceStage("response-ready")
                        if (!block.isCompleted) {
                            block.resume(response)
                        }
                    }

                    override fun onError(error: Throwable) {
                        traceStage("response-error")
                        if (!block.isCompleted)
                            block.resumeWithException(error)
                    }
                }
                if (javaScript == null && delayTime == 0L) {
                    delayTime = 900L
                }
                runOnUI {
                    if (!block.isActive) return@runOnUI
                    traceStage("load-main-entered")
                    try {
                        load(block.context)
                    } catch (error: Throwable) {
                        destroy()
                        block.resumeWithException(error)
                    }
                }
            }
        }
    } finally {
        // Await disposal even on timeout/cancellation; WebView destruction belongs to Main.
        withContext(NonCancellable + Main) { destroy() }
    }

    private fun getEncoding(): String {
        return encode ?: "utf-8"
    }

    @Throws(AndroidRuntimeException::class)
    private fun load(navigationContext: CoroutineContext) {
        val requestConfig = headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        val webView = createWebView(requestConfig)
        traceStage("webview-acquired")
        try {
            when {
                !html.isNullOrEmpty() -> {
                    if (isRule) {
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        tag?.let { key ->
                           appDb.bookSourceDao.getBookSource(key)?.let {
                               val source = (it as BaseSource).withSourceNavigationContext(navigationContext)
                               webView.addJavascriptInterface(source, nameSource)
                               val webJsExtensions = WebJsExtensions(it, null, webView,
                                   navigationContext = navigationContext)
                               webView.addJavascriptInterface(webJsExtensions, nameJava)
                            }
                        }
                    }
                    result?.let {
                        CacheManager.put("webview_result", it)
                    }
                    webView.loadDataWithBaseURL(url, html, "text/html", getEncoding(), url)
                    traceStage("inline-load-submitted")
                }

                else -> if (requestConfig.additionalHeaders.isEmpty()) {
                    webView.loadUrl(url!!)
                    traceStage("url-load-submitted")
                } else {
                    webView.loadUrl(url!!, requestConfig.additionalHeaders)
                    traceStage("url-load-submitted")
                }
            }
        } catch (e: Exception) {
            callback?.onError(e)
            destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(requestConfig: WebViewRequestConfig): WebView {
        // Rule/background loads have no window to restore a former UI WebView's host state.
        // Give them a fresh, request-owned instance and destroy it on completion/cancellation.
        val pooledWebView = WebViewPool.acquire(appCtx, recyclable = false)
        this.pooledWebView = pooledWebView
        val webView = pooledWebView.realWebView
        if (BuildConfig.DEBUG) traceStage("lease-acquired lease=${System.identityHashCode(pooledWebView)} " +
            "view=${System.identityHashCode(webView)} generation=${pooledWebView.recycleGeneration}")
        webView.onResume() //缓存库拿的需要激活
        val settings = webView.settings
        settings.blockNetworkImage = true
        settings.userAgentString = requestConfig.userAgent
        settings.cacheMode = if(cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        tag?.takeIf { it.isNotBlank() }?.let { sourceTag ->
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) traceStage("progress-complete")
                    super.onProgressChanged(view, newProgress)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    Debug.log(sourceTag, "$messageLevel: $message", true)
                    return true
                }
            }
        }
        if (sourceRegex.isNullOrBlank() && overrideUrlRegex.isNullOrBlank()) {
            webView.webViewClient = HtmlWebViewClient()
        } else {
            webView.webViewClient = SnifferWebClient()
        }
        if (BuildConfig.DEBUG) requestWebViewClient = webView.webViewClient
        return webView
    }

    private fun destroy() {
        val lease = pooledWebView ?: return
        pooledWebView = null
        traceStage("release-webview")
        WebViewPool.release(lease)
    }

    private fun getJs(): String {
        javaScript?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return JS
    }

    private fun setCookie(url: String) {
        tag?.let {
            Coroutine.async(executeContext = IO) {
                val cookie = CookieManager.getInstance().getCookie(url)
                CookieStore.setCookie(it, cookie)
            }
        }
    }

    private inner class HtmlWebViewClient : WebViewClient() {

        private var runnable: EvalJsRunnable? = null
        private var isRedirect = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            traceStage("page-started")
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (BuildConfig.DEBUG)
                traceStage("load-error mainFrame=${request?.isForMainFrame} code=${error?.errorCode}")
            super.onReceivedError(view, request, error)
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (BuildConfig.DEBUG) traceStage("renderer-gone crashed=${detail?.didCrash()}")
            return super.onRenderProcessGone(view, detail)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            isRedirect = isRedirect || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.isRedirect
            } else {
                request.url.toString() != view.url
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            traceStage("page-finished")
            setCookie(url)
            result?.let {
                view.evaluateJavascript("window.result = $nameCache.getFromMemory('webview_result')", null)
            }
            val runnable = runnable ?: EvalJsRunnable(view, url, getJs()).also {
                runnable = it
            }
            mHandler.removeCallbacks(runnable)
            mHandler.postDelayed(runnable, 100L + delayTime)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private inner class EvalJsRunnable(
            webView: WebView,
            private val url: String,
            mJavaScript: String
        ) : Runnable {
            private var retry = 0
            private val intervals = listOf(200L, 400L, 600L, 800L, 1000L)
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            private val jsStr = if (isRule) {
                "$getInjectionString\n$mJavaScript"
            } else mJavaScript
            override fun run() {
                traceStage("evaluate-dispatched")
                mWebView.get()?.evaluateJavascript(jsStr) {
                    traceStage(if (it.isNotEmpty() && it != "null") "evaluate-value" else "evaluate-empty")
                    if (pooledWebView != null) {
                        handleResult(it)
                    }
                }
            }

            private fun handleResult(result: String) = Coroutine.async {
                if (result.isNotEmpty() && result != "null") {
                    val content = StringEscapeUtils.unescapeJson(result)
                        .replace(quoteRegex, "")
                    try {
                        val response = buildStrResponse(content)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                if (retry > 30) {
                    callback?.onError(NoStackTraceException("js执行超时"))
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                val nextDelay = if (retry < intervals.size) {
                    intervals[retry]
                } else {
                    intervals.last()
                }
                retry++
                mHandler.postDelayed(this@EvalJsRunnable, nextDelay)
            }

            private fun buildStrResponse(content: String): StrResponse {
                if (!isRedirect) {
                    return StrResponse(url, content)
                }
                val originUrl = this@BackstageWebView.url ?: url
                val originResponse = Response.Builder()
                    .code(302)
                    .request(Request.Builder().url(originUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("Found")
                    .build()
                val response = Response.Builder()
                    .code(200)
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("OK")
                    .priorResponse(originResponse)
                    .build()
                return StrResponse(response, content)
            }
        }

    }

    private inner class SnifferWebClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (shouldOverrideUrlLoading(request.url.toString())) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (shouldOverrideUrlLoading(url)) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, url)
        }

        private fun shouldOverrideUrlLoading(requestUrl: String): Boolean {
            overrideUrlRegex?.let {
                if (requestUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, requestUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                    return true
                }
            }
            return false
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, resUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                }
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            setCookie(url)
            if (!javaScript.isNullOrEmpty()) {
                val runnable = LoadJsRunnable(webView, javaScript)
                mHandler.postDelayed(runnable, 100L + delayTime)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private inner class LoadJsRunnable(
            webView: WebView,
            private val mJavaScript: String?
        ) : Runnable {
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                mWebView.get()?.loadUrl("javascript:${mJavaScript}")
            }
        }

    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        private val quoteRegex = "^\"|\"$".toRegex()
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}
