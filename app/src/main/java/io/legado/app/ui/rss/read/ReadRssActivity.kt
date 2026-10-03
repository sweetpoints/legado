package io.legado.app.ui.rss.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.size
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.isTrue
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import splitties.views.bottomPadding
import java.io.ByteArrayInputStream
import java.util.regex.PatternSyntaxException
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.StartActivityContract
import kotlinx.coroutines.runBlocking
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.help.webView.shouldInjectPreloadJs
import io.legado.app.model.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import java.lang.ref.WeakReference
import splitties.systemservices.powerManager
import java.net.URLDecoder
import androidx.core.graphics.createBitmap

import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import java.util.UUID
import io.legado.app.model.rss.rssReaderImageOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal fun shouldPreserveRssArticleOnRefresh(
    ruleDescription: String?,
    ruleContent: String?,
) = ruleContent.isNullOrBlank() || !ruleDescription.isNullOrBlank()

/** Compose chrome around the retained, pooled RSS browser engine. */
class ReadRssActivity : BaseComposeActivity(showOpenMenuIcon = false), RssFavoritesDialog.Callback {
    internal val readerModel by viewModels<RssReaderViewModel> {
        viewModelFactory { initializer { RssReaderViewModel(AppRssReaderRepository(), FileRssReaderSessionRepository(),
            AppRssReaderSpeechRepository(), createSavedStateHandle().withoutReaderIntent()) } }
    }
    private val imageModel by viewModels<RssReaderImageViewModel> {
        viewModelFactory { initializer { RssReaderImageViewModel(AppRssReaderImageRepository(), FileRssReaderImageSessionRepository(),
            createSavedStateHandle().withoutReaderIntent()) } }
    }
    private fun SavedStateHandle.withoutReaderIntent() = apply {
        listOf("origin", "title", "link", "sort", "openUrl", "startHtml").forEach { remove<String>(it) }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    private lateinit var customWebView: FrameLayout
    private val kernel = UUID.randomUUID().toString()
    @Volatile private var readerSnapshot: RssReaderSnapshot? = null
    private var isFullscreen by mutableStateOf(false)
    private var wasScreenOff = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var interfaceInjected: String? = null
    private var needClearHistory = true
    // shouldInterceptRequest runs off the main thread; never read WebView state there.
    @Volatile
    private var currentPageUrl: String? = null
    private var imageChoice by mutableStateOf<String?>(null)
    private var dismissedImageError by mutableStateOf<String?>(null)
    private val snackbar = SnackbarHostState()
    private var pickerNonce: String? = null
    private var imageOwnerHash by mutableStateOf<String?>(null)
    private var imageBinding: Job? = null
    private var imageGeneration = 0L
    private val launchRequests by lazy { FileRssReaderLaunchRepository() }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        imagePickerResult(it.value, it.uri?.toString())
    }
    private val rssJsExtensions get() = RssJsExtensions(this, readerSnapshot?.source)
    private val refreshNameList = mutableListOf<String>()
    private val editSourceResult = registerForActivityResult(StartActivityContract(RssSourceEditActivity::class.java)) {
        if (it.resultCode == RESULT_OK) refresh()
    }
    private fun request(intent: Intent) = RssReaderRequest(intent.getStringExtra("origin"), intent.getStringExtra("title"),
        intent.getStringExtra("link"), intent.getStringExtra("sort"), intent.getStringExtra("openUrl"), intent.getStringExtra("startHtml"))
    private fun ready() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved
    internal fun imagePickerResult(value: String?, directory: String?) {
        val nonce = value ?: pickerNonce ?: return
        imageModel.picked(nonce, directory)
        if (nonce == pickerNonce) pickerNonce = null
    }
    private fun bindImages(request: RssReaderRequest) {
        imageOwnerHash = null; imageModel.invalidateOwner(); imageBinding?.cancel(); val epoch = ++imageGeneration
        imageBinding = lifecycleScope.launch {
            val hash = withContext(IO) { rssReaderImageOwner(request) }
            currentCoroutineContext().ensureActive()
            if (epoch == imageGeneration) { imageModel.bind(hash); imageOwnerHash = hash }
        }
    }
    private fun bindPreparedReader(ticket: String) {
        // Invalidate the old image owner before private request IO can suspend.
        imageOwnerHash = null; imageModel.invalidateOwner(); imageBinding?.cancel(); val epoch = ++imageGeneration
        readerModel.bindPrepared(ticket, launchRequests)
        imageBinding = lifecycleScope.launch {
            readerModel.state.map { it.loaded }.distinctUntilChanged().collectLatest { loaded ->
                if (!loaded) return@collectLatest
                val input = readerModel.snapshot()?.request ?: return@collectLatest
                val hash = withContext(IO) { rssReaderImageOwner(input) }
                currentCoroutineContext().ensureActive()
                if (epoch == imageGeneration) { imageModel.bind(hash); imageOwnerHash = hash }
            }
        }
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        pickerNonce = savedInstanceState?.getString("rssReader.pickerNonce")
        pooledWebView = WebViewPool.acquire(this); currentWebView = pooledWebView.realWebView
        customWebView = FrameLayout(this)
        initWebView(); currentWebView.clearHistory()
        val ticket = intent.getStringExtra(PREPARED_REQUEST)
        if (ticket != null) bindPreparedReader(ticket)
        else {
            readerModel.bind(if (savedInstanceState == null) request(intent) else null)
            bindImages(request(intent))
        }
        onBackPressedDispatcher.addCallback(this) { browserBack() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pickerNonce?.let { outState.putString("rssReader.pickerNonce", it) }; super.onSaveInstanceState(outState)
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        val imageState by imageModel.state.collectAsStateWithLifecycle()
        RssReaderRoute(readerModel, imageModel, kernel, imageOwnerHash, isFullscreen, ::ready, ::browserBack, ::loadDocument, ::native, ::imageEffect,
            snackbar = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar) } },
            browser = { AndroidView(factory = { currentWebView }, modifier = Modifier.fillMaxSize()) },
            customVideo = { AndroidView(factory = { customWebView }, modifier = Modifier.fillMaxSize()) })
        imageChoice?.let { image ->
            AlertDialog(onDismissRequest = { imageChoice = null }, title = { Text(stringResource(R.string.action_save)) },
                text = { Column {
                    TextButton({ imageChoice = null; dismissedImageError = null; imageOwnerHash?.let { imageModel.save(image, it) } }, enabled = imageOwnerHash != null) { Text(stringResource(R.string.action_save)) }
                    TextButton({ imageChoice = null; imageOwnerHash?.let(imageModel::chooseDirectory) }, enabled = imageOwnerHash != null) { Text(stringResource(R.string.select_folder)) }
                } }, confirmButton = {})
        }
        imageState.error?.takeUnless { it == dismissedImageError }?.let { error ->
            AlertDialog(onDismissRequest = { dismissedImageError = error }, title = { Text(stringResource(R.string.action_save)) }, text = { Text(error) },
                confirmButton = { TextButton({ dismissedImageError = null; imageModel.retry() }) { Text(stringResource(R.string.retry)) } },
                dismissButton = { TextButton({ dismissedImageError = error }) { Text(stringResource(R.string.cancel)) } })
        }
    }
    override fun onNewIntent(intent: Intent) {
        val ticket = intent.getStringExtra(PREPARED_REQUEST)
        val duplicate = ticket != null && ticket == this.intent.getStringExtra(PREPARED_REQUEST) && readerModel.state.value.loaded
        super.onNewIntent(intent); setIntent(intent)
        if (duplicate) return
        imageChoice = null; dismissedImageError = null
        currentWebView.stopLoading(); readerSnapshot = null
        if (ticket != null) bindPreparedReader(ticket)
        else { readerModel.bind(request(intent)); bindImages(request(intent)) }
    }
    private fun refresh() {
        if (readerSnapshot?.source?.singleUrl == true) { currentWebView.reload(); return }
        currentWebView.title?.let(refreshNameList::add)
        readerModel.snapshot()?.article?.let {
            if (shouldPreserveRssArticleOnRefresh(readerSnapshot?.source?.ruleDescription, readerSnapshot?.source?.ruleContent))
                start(this, it.origin, it.title, it.link, it.sort)
            else start(this, true, it.origin, it.title, it.link)
        } ?: readerModel.bind(request(intent))
    }
    private fun native(effect: RssReaderEffect) {
        when (effect.action) {
            RssReaderAction.Refresh -> refresh()
            RssReaderAction.Favorite -> readerModel.snapshot()?.article?.let { showDialogFragment(RssFavoritesDialog(it)) }
            RssReaderAction.Share -> (currentWebView.url ?: readerModel.snapshot()?.article?.link)?.let { share(it) } ?: toastOnUi(R.string.null_url)
            RssReaderAction.Speech -> {
                currentWebView.settings.javaScriptEnabled = true
                currentWebView.evaluateJavascript("document.documentElement.outerHTML") { readerModel.speakHtml(it, kernel, effect.nonce) }
            }
            RssReaderAction.Login -> startActivity<SourceLoginActivity> { putExtra("type", "rssSource"); putExtra("key", readerSnapshot?.source?.sourceUrl) }
            RssReaderAction.Browser -> currentWebView.url?.let { openUrl(it) } ?: toastOnUi(R.string.null_url)
            RssReaderAction.ReadRecords -> showDialogFragment(ReadRecordDialog(readerSnapshot?.source?.sourceUrl))
            RssReaderAction.EditSource -> readerSnapshot?.source?.sourceUrl?.let { source -> editSourceResult.launch { putExtra("sourceUrl", source) } }
            RssReaderAction.Log -> showDialogFragment<AppLogDialog>()
        }
    }
    private fun imageEffect(effect: RssReaderImageEffect, previous: String?) {
        when (effect.kind) {
            RssReaderImageEffectKind.Saved -> toastOnUi(R.string.success)
            RssReaderImageEffectKind.Picker -> {
                pickerNonce = effect.nonce
                try {
                    selectImageDir.launch { value = effect.nonce; otherActions = arrayListOf<SelectItem<Int>>().apply { previous?.takeIf { it.isNotEmpty() }?.let { add(SelectItem(it, -1)) } } }
                } catch (error: Exception) {
                    pickerNonce = null; imageModel.picked(effect.nonce, null); toastOnUi(error.localizedMessage ?: error.javaClass.simpleName)
                }
            }
        }
    }
    override fun updateFavorite(title: String?, group: String?) { readerModel.updateFavorite(title, group) }
    override fun deleteFavorite() { readerModel.deleteFavorite() }
    private fun snack(message: String, action: String, click: () -> Unit) {
        lifecycleScope.launch {
            if (snackbar.showSnackbar(message, action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed && ready()) click()
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.setOnLongClickListener {
            val hit = currentWebView.hitTestResult
            if (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hit.extra?.let { imageChoice = it; return@setOnLongClickListener true }
            }
            false
        }
        currentWebView.setDownloadListener { url, _, disposition, _, _ ->
            val name = URLDecoder.decode(URLUtil.guessFileName(url, disposition, null), "UTF-8")
            snack(name, getString(R.string.action_download)) { Download.start(this, url, name) }
        }
    }
    private fun loadDocument(snapshot: RssReaderSnapshot) {
        readerSnapshot = snapshot; bindImages(snapshot.request)
        val document = snapshot.document ?: return
        upWebviewSettings((document as? RssReaderDocument.Url)?.userAgent); initJavascriptInterface()
        when (document) {
            is RssReaderDocument.Url -> {
                CookieManager.applyToWebView(document.url); currentPageUrl = document.url
                currentWebView.loadUrl(document.url, document.headers)
            }
            is RssReaderDocument.Html -> {
                currentPageUrl = document.historyUrl
                currentWebView.loadDataWithBaseURL(document.baseUrl, document.html, "text/html", "utf-8", document.historyUrl)
            }
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun upWebviewSettings(userAgent: String? = null) {
        readerSnapshot?.source?.let { source -> currentWebView.settings.run {
            userAgentString = userAgent ?: readerSnapshot!!.headers.toWebViewRequestConfig(AppConfig.userAgent).userAgent
            javaScriptEnabled = source.enableJs
            cacheMode = if (source.cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        } }
    }
    private fun initJavascriptInterface() {
        readerSnapshot?.source?.let { source ->
            if (interfaceInjected != source.sourceUrl) {
                interfaceInjected = source.sourceUrl
                if (source.preloadJs.isNullOrBlank()) return
                currentWebView.addJavascriptInterface(WebJsExtensions(source, this, currentWebView), nameJava)
                currentWebView.addJavascriptInterface(source, nameSource)
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
        }
    }
    override fun onPause() {
        super.onPause()
        if (::currentWebView.isInitialized) {
            if (powerManager.isInteractive) { wasScreenOff = false; currentWebView.onPause() } else wasScreenOff = true
        }
    }
    override fun onResume() { super.onResume(); if (::currentWebView.isInitialized && !wasScreenOff) currentWebView.onResume() }
    override fun onDestroy() {
        imageChoice = null
        if (::customWebView.isInitialized) customWebView.removeAllViews()
        customWebViewCallback = null; readerModel.detachKernel(kernel)
        if (::pooledWebView.isInitialized) WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    private fun browserBack() {
            if (customWebView.size > 0) { //关闭全屏
                customWebViewCallback?.onCustomViewHidden()
                if (isFullscreen) currentWebView.webChromeClient?.onHideCustomView()
                return
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList() //获取历史列表
                val size = list.size
                if (size == 1) {
                    finish()
                    return
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                //从后往前找，找到第一个不同链接的页面，计算需要回退多少步 避免刷新后导致返回不灵
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemTitle = item.title
                    val index = refreshNameList.indexOf(itemTitle)
                    if (index != -1) {
                        refreshNameList.removeAt(index)
                        steps++
                        continue
                    }
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return
                    }
                    if (itemUrl != currentUrl || itemTitle != currentTitle) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return
                }
                currentWebView.goBackOrForward(-steps)
                return
            }
            finish()
        }
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    @Suppress("unused")
    private class JSInterface(activity: ReadRssActivity) {
        private val activityRef: WeakReference<ReadRssActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isFullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.finish()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            readerModel.progress(newProgress)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isFullscreen = true
            customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
            if (readerSnapshot?.source?.enableJs == false) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        override fun onHideCustomView() {
            isFullscreen = false
            customWebView.removeAllViews()
            customWebViewCallback = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            finish()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            readerSnapshot?.source?.let { source ->
                if (source.showWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(request.url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldOverrideUrlLoading(url.toUri())
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            currentPageUrl = url
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        private var jsInjected = false
        /**
         * 如果有黑名单,黑名单匹配返回空白,
         * 没有黑名单再判断白名单,在白名单中的才通过,
         * 都没有不做处理
         */
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            val source = readerSnapshot?.source ?: return super.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) {
                if ((!readerSnapshot?.source?.preloadJs.isNullOrBlank())) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return runBlocking(IO) {
                        getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = source.preloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            val blacklist = source.contentBlacklist?.splitNotBlank(",")
            if (!blacklist.isNullOrEmpty()) {
                blacklist.forEach {
                    try {
                        if (url.startsWith(it) || url.matches(it.toRegex())) {
                            return createEmptyResource()
                        }
                    } catch (e: PatternSyntaxException) {
                        AppLog.put("黑名单规则正则语法错误 源名称:${source.sourceName} 正则:$it", e)
                    }
                }
            } else {
                val whitelist = source.contentWhitelist?.splitNotBlank(",")
                if (!whitelist.isNullOrEmpty()) {
                    var matched = false
                    whitelist.forEach {
                        try {
                            if (url.startsWith(it) || url.matches(it.toRegex())) {
                                matched = true
                            }
                        } catch (e: PatternSyntaxException) {
                            val msg = "白名单规则正则语法错误 源名称:${source.sourceName} 正则:$it"
                            AppLog.put(msg, e)
                        }
                    }
                    if (!matched) return createEmptyResource()
                }
            }
            if (AppConfig.isCronet && RssWebResourceProxy.shouldProxy(
                    url = url,
                    method = request.method,
                    isForMainFrame = request.isForMainFrame,
                    requestHeaders = request.requestHeaders,
                    preloadUrl = nameUrl,
                )
            ) {
                return runBlocking(IO) { getProxiedResource(request) }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private suspend fun getProxiedResource(request: WebResourceRequest): WebResourceResponse {
            val url = request.url.toString()
            val sameOrigin = isSameOrigin(url)
            val sourceCookie = if (sameOrigin) {
                (readerSnapshot?.headers ?: emptyMap()).entries.firstOrNull {
                    it.key.equals("Cookie", ignoreCase = true)
                }?.value
            } else null
            val cookie = runCatching {
                CookieManager.mergeCookies(
                    sourceCookie,
                    runCatching { CookieStore.getCookie(url) }.getOrNull(),
                    runCatching { webCookieManager.getCookie(url) }.getOrNull(),
                )
            }.getOrNull()
            val response = try {
                okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    RssWebResourceProxy.requestHeaders(
                        sourceHeaders = if (sameOrigin) (readerSnapshot?.headers ?: emptyMap()) else emptyMap(),
                        webViewHeaders = request.requestHeaders,
                        cookie = cookie,
                    ).forEach { (name, value) -> header(name, value) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("RSS 子资源 Cronet 请求失败\n$url\n${error.localizedMessage}", error)
                return createProxyErrorResource()
            }

            response.headers("Set-Cookie").forEach { setCookie ->
                webCookieManager.setCookie(url, setCookie)
            }
            if (!RssWebResourceProxy.supportsStatus(response.code)) {
                response.close()
                AppLog.put("RSS 子资源收到不支持的重定向状态 ${response.code}\n$url")
                return createProxyErrorResource()
            }

            val body = response.body
            val contentType = body.contentType()?.toString()
            return WebResourceResponse(
                RssWebResourceProxy.mimeType(contentType) ?: "application/octet-stream",
                RssWebResourceProxy.encoding(contentType) ?: "utf-8",
                RssProxyResponseInputStream(response, body),
            ).also { webResponse ->
                webResponse.setStatusCodeAndReasonPhrase(
                    response.code,
                    RssWebResourceProxy.reasonPhrase(response.message),
                )
                webResponse.responseHeaders = RssWebResourceProxy.responseHeaders(response.headers)
            }
        }

        private fun createProxyErrorResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("RSS resource unavailable".toByteArray()),
            ).also {
                it.setStatusCodeAndReasonPhrase(502, "Bad Gateway")
            }
        }

        private fun isSameOrigin(url: String): Boolean {
            val target = url.toUri()
            if (target.scheme !in setOf("http", "https") || target.host.isNullOrBlank()) return false
            val page = sequenceOf(
                currentPageUrl,
                readerSnapshot?.article?.let { article ->
                    NetworkUtils.getAbsoluteURL(article.origin, article.link)
                },
            ).filterNotNull()
                .map(String::toUri)
                .firstOrNull { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
                ?: return false
            return target.scheme.equals(page.scheme, ignoreCase = true)
                && target.host.equals(page.host, ignoreCase = true)
                && (target.port == page.port || target.port == -1 && page.port == -1)
        }

        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.headers("Set-Cookie").forEach { setCookie ->
                    webCookieManager.setCookie(url, setCookie)
                }
                val body = res.body
                val contentType = body.contentType()
                if (!shouldInjectPreloadJs(contentType, res.header("Content-Disposition"))) {
                    res.close()
                    return null
                }
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val charsetSre = charset.name()
                val bodyText = body.text().let { originalText ->
                    val headIndex = originalText.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            val insertPos = closingHeadIndex + 1
                            StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                        } else {
                            originalText
                        }
                    } else {
                        originalText
                    }
                }
                return WebResourceResponse(
                    mimeType,
                    charsetSre,
                    ByteArrayInputStream(bodyText.toByteArray(charset))
                )
            } catch (_: Exception) {
                return null
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (URLUtil.isNetworkUrl(url)) {
                webCookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { cookie ->
                    CookieStore.setCookie(url, cookie)
                }
            }
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != BLANK_HTML
                    && !url.contains(title)) {
                    readerModel.page(url, title)
                } else {
                    readerModel.page(url, null)
                }
            }
            readerSnapshot?.source?.injectJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
        }

        private fun createEmptyResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "utf-8", ByteArrayInputStream("".toByteArray())
            )
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            readerSnapshot?.source?.let { source ->
                source.shouldOverrideUrlLoading?.takeUnless(String::isNullOrBlank)?.let { js ->
                    val startTime = SystemClock.uptimeMillis()
                    val result = runCatching {
                        runScriptWithContext(lifecycleScope.coroutineContext) {
                            source.evalJS(js) {
                                put("java", rssJsExtensions)
                                put("url", url.toString())
                            }.toString()
                        }
                    }.onFailure {
                        AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                    }.getOrNull()
                    if (SystemClock.uptimeMillis() - startTime > 99) {
                        AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                    }
                    if (result.isTrue()) return true
                }
            }
            return handleCommonSchemes(url)
        }

        private fun handleCommonSchemes(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> { data = url }
                    true
                }

                else -> {
                    snack(getString(R.string.jump_to_another_app), getString(R.string.confirm)) { openUrl(url) }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }

    }

    companion object {
        const val PREPARED_REQUEST = "rssReader.launchTicket"

        /** Complete input is staged on IO by the caller; only this canonical UUID reaches Binder. */
        fun startPrepared(context: Context, ticket: String, singleTop: Boolean = true) {
            require(UUID.fromString(ticket).toString() == ticket)
            context.startActivity<ReadRssActivity> {
                putExtra(PREPARED_REQUEST, ticket)
                if (singleTop) addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        fun start(context: Context, singleTop: Boolean, origin: String, title: String? = null, url: String? = null, startHtml: String? = null) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("openUrl", url)
                putExtra("startHtml", startHtml)
                if (singleTop) {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
        }

        /**
         * 知晓rssArticle的打开
         */
        fun start(context: Context, origin: String, title: String?, link: String, sort: String) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("link", link)
                putExtra("sort", sort)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) //栈顶复用
            }
        }

        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
    }

}
