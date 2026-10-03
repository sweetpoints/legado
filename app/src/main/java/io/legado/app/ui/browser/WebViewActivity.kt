package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.repository.*
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.*
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.Download
import io.legado.app.model.browser.*
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.*
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import splitties.systemservices.powerManager

/** Compose owns the page; these two frames contain only native WebView/video rendering cores. */
class WebViewActivity : BaseComposeActivity() {
    companion object {
        var sessionShowWebLog = false
    }

    private lateinit var pooledWebView: PooledWebView
    internal lateinit var currentWebView: WebView
        private set

    private lateinit var webCore: FrameLayout
    private lateinit var videoCore: FrameLayout
    private var retired = false
    private var installed = false
    private var modelCreated = false
    private var source: BaseSource? = null
    private var documentReady by mutableStateOf(false)
    private var videoShown by mutableStateOf(false)
    private var log by mutableStateOf(sessionShowWebLog)
    private val snackbar = SnackbarHostState()
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenApplied: Boolean? = null
    private var wasScreenOff = false
    private var needClearHistory = true
    internal val model by
        viewModels<BrowserViewModel> {
            viewModelFactory {
                initializer {
                    val request =
                        BrowserRequest(
                            intent.getStringExtra("url").orEmpty(),
                            intent.getStringExtra("title").orEmpty(),
                            intent.getStringExtra("sourceName").orEmpty(),
                            intent.getStringExtra("sourceOrigin").orEmpty(),
                            intent.getIntExtra("sourceType", 0),
                            intent.getStringExtra("html"),
                            intent.getBooleanExtra("sourceVerificationEnable", false),
                            intent.getBooleanExtra("refetchAfterSuccess", true),
                            intent.getStringExtra("verificationResultKey"),
                        )
                    val saved =
                        createSavedStateHandle().apply {
                            listOf(
                                    "url",
                                    "title",
                                    "sourceName",
                                    "sourceOrigin",
                                    "sourceType",
                                    "html",
                                    "sourceVerificationEnable",
                                    "refetchAfterSuccess",
                                    "verificationResultKey",
                                )
                                .forEach { remove<Any?>(it) }
                        }
                    BrowserViewModel(
                        DefaultBrowserRepository(
                            AppBrowserDataStore(),
                            AppBrowserSessionStore(applicationContext),
                        ),
                        saved,
                    ) {
                        request
                    }
                }
            }
        }
    private val saveImage =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { if (ready()) model.saveImage(it.toString(), remember = true) }
        }

    private fun ready() = !retired && !isFinishing && !isDestroyed

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (
            !SourceVerificationHelp.attachVerificationUi(
                intent.getStringExtra("verificationResultKey"),
                ::finishVerificationUi,
            )
        ) {
            finish()
            return
        }
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        webCore =
            FrameLayout(this).apply { addView(currentWebView, FrameLayout.LayoutParams(-1, -1)) }
        videoCore = FrameLayout(this)
        currentWebView.clearHistory()
        currentWebView.post { if (ready()) currentWebView.clearHistory() }
        modelCreated = true
        model // Initialize before pause/destroy, without storing the Activity in the ViewModel.
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        if (!::currentWebView.isInitialized || !ready()) return
        BrowserRoute(
            model,
            BrowserHostActions(
                ::ready,
                { installed },
                ::install,
                ::capture,
                ::receipt,
                ::selectSaveFolder,
                ::back,
                ::finish,
                ::menu,
                ::fullscreen,
            ),
            webCore,
            if (videoShown) videoCore else null,
            documentReady,
            log,
            snackbar,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun install(delivery: BrowserPageDelivery) {
        if (installed || !ready()) return
        installed = true
        source = delivery.source
        val page = delivery.page
        val config = page.headers.toWebViewRequestConfig(page.userAgent)
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = config.userAgent
        }
        delivery.cookies?.let { cookies ->
            val manager = CookieManager.getInstance()
            manager.removeSessionCookies(null)
            cookies.values.forEach { manager.setCookie(cookies.baseUrl, it) }
        }
        currentWebView.setOnLongClickListener {
            if (!ready()) return@setOnLongClickListener false
            val hit = currentWebView.hitTestResult
            if (
                hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                hit.extra?.let {
                    model.image(it)
                    return@setOnLongClickListener true
                }
            }
            false
        }
        currentWebView.setDownloadListener { url, _, disposition, _, _ ->
            if (ready()) {
                val fileName =
                    URLDecoder.decode(URLUtil.guessFileName(url, disposition, null), "UTF-8")
                message(fileName, getString(R.string.action_download)) {
                    Download.start(this, url, fileName)
                }
            }
        }
        if (page.html.isNullOrEmpty())
            currentWebView.loadUrl(page.baseUrl, config.additionalHeaders)
        else {
            if (page.localHtml) {
                source?.let {
                    currentWebView.addJavascriptInterface(
                        WebJsExtensions(it, this, currentWebView),
                        nameJava,
                    )
                }
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
            currentWebView.loadDataWithBaseURL(
                page.baseUrl,
                page.html,
                "text/html",
                "utf-8",
                page.baseUrl,
            )
        }
    }

    private fun capture(id: String) {
        currentWebView.evaluateJavascript("document.documentElement.outerHTML") { html ->
            if (ready()) model.captured(id, html, currentWebView.url.orEmpty())
        }
    }

    private fun receipt(value: BrowserReceipt) {
        when (value.kind) {
            BrowserReceiptKind.Verified ->
                value.verification?.let {
                    SourceVerificationHelp.setResult(
                        intent.getStringExtra("verificationResultKey"),
                        it.html,
                        it.url,
                    )
                }
            BrowserReceiptKind.ImageSaved -> toastOnUi("保存成功")
            BrowserReceiptKind.ImageFailed -> toastOnUi("保存图片失败:${value.message.orEmpty()}")
            BrowserReceiptKind.Close -> Unit
        }
    }

    private fun menu(action: BrowserMenu) {
        when (action) {
            BrowserMenu.Refresh -> {
                documentReady = false
                currentWebView.reload()
            }
            BrowserMenu.Open -> model.state.value.page?.let { openUrl(it.baseUrl) }
            BrowserMenu.Copy -> model.state.value.page?.let { sendToClip(it.baseUrl) }
            BrowserMenu.Log -> {
                sessionShowWebLog = !sessionShowWebLog
                log = sessionShowWebLog
            }
            else -> Unit // Durable actions belong to the ViewModel.
        }
    }

    private fun selectSaveFolder(path: String?) {
        saveImage.launch {
            otherActions =
                arrayListOf<SelectItem<Int>>().apply {
                    if (!path.isNullOrEmpty()) add(SelectItem(path, -1))
                }
        }
    }

    private fun fullscreen(value: Boolean) {
        if (fullscreenApplied == null && !value) {
            fullscreenApplied = false
            return
        }
        if (fullscreenApplied != value) {
            fullscreenApplied = value
            if (!videoShown) toggleSystemBar(!value)
        }
    }

    private fun back() {
        val list = currentWebView.copyBackForwardList()
        val history =
            (0 until list.size).map { index ->
                list.getItemAtIndex(index).let { BrowserHistoryItem(it.originalUrl, it.title) }
            }
        when (
            val action =
                browserBackAction(
                    videoShown,
                    model.state.value.fullscreen,
                    currentWebView.canGoBack(),
                    history,
                    list.currentIndex,
                )
        ) {
            BrowserBackAction.HideVideo -> {
                customCallback?.onCustomViewHidden()
                if (videoShown) hideVideo()
            }
            BrowserBackAction.ExitFullscreen -> model.fullscreen(false)
            BrowserBackAction.Close -> finish()
            is BrowserBackAction.GoBack -> currentWebView.goBackOrForward(-action.steps)
        }
    }

    private fun message(text: String, action: String, invoke: () -> Unit) {
        lifecycleScope.launch {
            if (
                snackbar.showSnackbar(text, action, duration = SnackbarDuration.Long) ==
                    SnackbarResult.ActionPerformed
            ) {
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                if (ready()) invoke()
            }
        }
    }

    private fun hideVideo() {
        if (!videoShown) return
        videoShown = false
        videoCore.removeAllViews()
        customCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        keepScreenOn(false)
        toggleSystemBar(true)
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(intent.getStringExtra("verificationResultKey"))
        super.finish()
    }

    private fun finishVerificationUi() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish()
            return
        }
        val finished = CountDownLatch(1)
        runOnUiThread {
            try {
                finish()
            } finally {
                finished.countDown()
            }
        }
        finished.await(5, SECONDS)
    }

    override fun onPause() {
        super.onPause()
        if (::currentWebView.isInitialized) {
            wasScreenOff = !powerManager.isInteractive
            if (!wasScreenOff) currentWebView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::currentWebView.isInitialized && !wasScreenOff) currentWebView.onResume()
    }

    override fun onStop() {
        if (modelCreated && !isFinishing) {
            val captured = model
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }
                .onError { AppLog.put("保存网页会话失败", it) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        retired = true
        if (modelCreated && isFinishing) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.releaseOwnedSession() }
                .onError { AppLog.put("清理网页会话失败", it) }
        }
        if (::pooledWebView.isInitialized) {
            customCallback?.onCustomViewHidden()
            hideVideo()
            // Only this owner's cores are detached; a pooled WebView can subsequently belong to
            // another Activity.
            webCore.removeAllViews()
            videoCore.removeAllViews()
            WebViewPool.release(pooledWebView)
        }
        super.onDestroy()
    }

    @Suppress("unused")
    private class JSInterface(activity: WebViewActivity) {
        private val reference = WeakReference(activity)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            reference.get()?.let { owner ->
                owner.runOnUiThread {
                    if (owner.ready() && owner.videoShown)
                        owner.requestedOrientation =
                            when (orientation) {
                                "portrait",
                                "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                "portrait-secondary" ->
                                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                "landscape-secondary" ->
                                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                                "any",
                                "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            reference.get()?.let { owner ->
                owner.runOnUiThread { if (owner.ready()) owner.model.windowClose() }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap =
            super.getDefaultVideoPoster() ?: createBitmap(100, 100)

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (ready()) model.progress(newProgress)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (!ready() || view == null || videoShown) {
                callback?.onCustomViewHidden()
                return
            }
            videoCore.addView(view, FrameLayout.LayoutParams(-1, -1))
            customCallback = callback
            videoShown = true
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            hideVideo()
        }

        override fun onCloseWindow(window: WebView?) {
            if (ready()) model.windowClose()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            source?.let {
                if (ready() && sessionShowWebLog) {
                    val message = consoleMessage.message()
                    AppLog.put(
                        "${it.getTag()}${consoleMessage.messageLevel().name}: $message",
                        NoStackTraceException(
                            "\n$message\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"
                        ),
                    )
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = request?.let { overrideUrl(it.url) } ?: true

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            url?.let { overrideUrl(it.toUri()) } ?: true

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!ready()) return
            documentReady = false
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory()
            }
            currentWebView.evaluateJavascript(basicJs, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || !ready() || view !== currentWebView) return
            documentReady = true
            model.progress(100)
            url?.let { model.cookie(it, CookieManager.getInstance().getCookie(it)) }
            view.title?.let { title ->
                model.title(
                    if (title != url && title != view.url && title.isNotBlank()) title
                    else intent.getStringExtra("title").orEmpty()
                )
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (ready()) model.challenge(it == "true")
                }
            }
        }

        private fun overrideUrl(url: Uri): Boolean =
            when (url.scheme) {
                "http",
                "https" -> false
                "legado",
                "yuedu" -> {
                    lifecycleScope.launch {
                        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                        if (ready()) startActivity<OnLineImportActivity> { data = url }
                    }
                    true
                }
                else -> {
                    if (ready())
                        message(
                            getString(R.string.jump_to_another_app),
                            getString(R.string.confirm),
                        ) {
                            openUrl(url)
                        }
                    true
                }
            }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            if (ready()) handler?.proceed()
        }
    }
}
