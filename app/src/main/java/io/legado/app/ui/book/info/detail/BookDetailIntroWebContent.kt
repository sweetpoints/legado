package io.legado.app.ui.book.info.detail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.repository.DictionaryImageData
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.ui.dict.DictionaryResultAction
import io.legado.app.utils.setDarkeningAllowed
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Only the content renderer is a platform view; all page controls and text-only intros are Compose.
 */
@Composable
internal fun BookDetailIntroWebContent(
    document: BookDetailIntroDocument,
    bookUrl: String,
    source: BaseSource?,
    expanded: Boolean,
    image: suspend (String) -> DictionaryImageData,
    onAction: (DictionaryResultAction) -> Unit,
    onLink: (String) -> Unit,
    onImage: (String) -> Unit,
    onOverflow: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val latestImage by rememberUpdatedState(image)
    val latestAction by rememberUpdatedState(onAction)
    val latestLink by rememberUpdatedState(onLink)
    val latestPhoto by rememberUpdatedState(onImage)
    val latestOverflow by rememberUpdatedState(onOverflow)
    var height by remember(document.signature) { mutableStateOf(80.dp) }
    var view by remember { mutableStateOf<BookDetailIntroNativeView?>(null) }
    DisposableEffect(owner, view) {
        val observer = LifecycleEventObserver { _, _ ->
            view?.syncLifecycle(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    key(document.mode, source?.getKey(), document.signature) {
        AndroidView(
            factory = { context ->
                BookDetailIntroNativeView(
                        context,
                        { latestImage(it) },
                        { latestAction(it) },
                        { latestLink(it) },
                        { latestPhoto(it) },
                        { pixels -> height = with(density) { pixels.coerceAtLeast(1).toDp() } },
                        { latestOverflow(it) },
                    )
                    .also {
                        view = it
                        it.syncLifecycle(
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        )
                    }
            },
            modifier = modifier.height(height),
            onRelease = {
                it.release()
                if (view === it) view = null
            },
            update = {
                it.syncLifecycle(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                it.display(
                    document,
                    bookUrl,
                    source,
                    expanded,
                    colors.surface.toArgb(),
                    colors.onSurface.toArgb(),
                    colors.primary.toArgb(),
                    density.fontScale,
                )
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class BookDetailIntroNativeView(
    context: Context,
    private val image: suspend (String) -> DictionaryImageData,
    private val action: (DictionaryResultAction) -> Unit,
    private val link: (String) -> Unit,
    private val photo: (String) -> Unit,
    private val size: (Int) -> Unit,
    private val overflow: (Boolean) -> Unit,
) : WebView(context) {
    private val released = AtomicBoolean()
    private val images = SupervisorJob()
    private val contentScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var contentJob: Job? = null
    private var contentGeneration = 0L
    private var loadedGeneration = 0L
    private var loadedBase: String? = null
    @Volatile private var document: BookDetailIntroDocument? = null
    private var key: List<Any?>? = null
    private var source: BaseSource? = null
    private var bookUrl = ""
    private var expanded = true
    private var fullMeasured = false
    private var collapsed = false
    private var background = 0
    private var text = 0
    private var accent = 0
    private var fontScale = 1f
    private var resumed: Boolean? = null
    private var reportPosted = false
    private var pageReady = false
    private var lastPixels = -1
    val isReleased: Boolean
        get() = released.get()

    val isPageReady: Boolean
        get() = pageReady

    val isResumed: Boolean
        get() = resumed == true

    init {
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = false
            defaultFontSize = 14
            domStorageEnabled = false
            allowContentAccess = false
            allowFileAccess = false
        }
        webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (!released.get() && document?.mode == BookDetailIntroMode.Web)
                        evaluateJavascript(WebJsExtensions.getInjectionString, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val generation = loadedGeneration
                    if (released.get() || url == "about:blank" || generation != contentGeneration)
                        return
                    if (document?.mode != BookDetailIntroMode.Web && url != loadedBase) return
                    post {
                        if (!released.get() && generation == contentGeneration) {
                            pageReady = true
                            reportSize()
                            requestLayout()
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) =
                    navigate(request.url.toString())

                @Deprecated("Old platform callback")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?) =
                    url?.let(::navigate) ?: true

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val current = document ?: return emptyResponse()
                    if (current.mode == BookDetailIntroMode.Web) return null
                    val original = current.rich?.image(request.url.toString())
                    if (original != null && !released.get())
                        return try {
                            val bytes = runBlocking(images + Dispatchers.IO) { image(original) }
                            if (released.get()) emptyResponse()
                            else
                                WebResourceResponse(
                                    bytes.mime,
                                    null,
                                    ByteArrayInputStream(bytes.bytes),
                                )
                        } catch (_: Exception) {
                            emptyResponse()
                        }
                    return if (request.isForMainFrame) null else emptyResponse()
                }
            }
        setOnLongClickListener {
            val hit = hitTestResult
            val url = hit?.extra.orEmpty()
            val original =
                document?.rich?.image(url)
                    ?: url.takeIf { document?.mode == BookDetailIntroMode.Web }
            if (
                original != null &&
                    (hit?.type == HitTestResult.IMAGE_TYPE ||
                        hit?.type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                photo(original)
                true
            } else false
        }
    }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

    internal fun navigate(url: String): Boolean {
        if (released.get()) return true
        val current = document ?: return true
        if (current.mode == BookDetailIntroMode.Web) {
            if (Uri.parse(url).scheme in setOf("http", "https")) return false
            link(url)
            return true
        }
        current.rich?.action(url)?.let {
            action(it)
            return true
        }
        val host = Uri.parse(url).host
        if (
            host in
                setOf(
                    "dictionary-action.invalid",
                    "dictionary-image.invalid",
                    "dictionary-content.invalid",
                )
        )
            return true
        link(url)
        return true
    }

    fun display(
        next: BookDetailIntroDocument,
        url: String,
        nextSource: BaseSource?,
        showExpanded: Boolean,
        background: Int,
        text: Int,
        accent: Int,
        fontScale: Float,
    ) {
        if (released.get()) return
        val nextKey =
            listOf(
                next.signature,
                url,
                nextSource?.getKey(),
                showExpanded,
                background,
                text,
                accent,
                fontScale,
            )
        if (nextKey == key) return
        key = nextKey
        document = next
        bookUrl = url
        source = nextSource
        expanded = showExpanded
        this.background = background
        this.text = text
        this.accent = accent
        this.fontScale = fontScale
        fullMeasured = false
        collapsed = false
        lastPixels = -1
        removeJavascriptInterface(WebJsExtensions.nameCache)
        removeJavascriptInterface(WebJsExtensions.nameSource)
        removeJavascriptInterface(WebJsExtensions.nameJava)
        val web = next.mode == BookDetailIntroMode.Web
        settings.apply {
            javaScriptEnabled = web
            domStorageEnabled = web
            allowContentAccess = web
            mixedContentMode =
                if (web) WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                else WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = !web
            builtInZoomControls = web
            displayZoomControls = false
            textZoom = if (web) 100 else (fontScale * 100).toInt().coerceAtLeast(1)
            if (web) {
                userAgentString = AppConfig.userAgent
                setDarkeningAllowed(AppConfig.isNightTheme)
            }
        }
        if (web) {
            addJavascriptInterface(WebCacheManager, WebJsExtensions.nameCache)
            nextSource?.let {
                addJavascriptInterface(it, WebJsExtensions.nameSource)
                addJavascriptInterface(WebJsExtensions(it, null, this), WebJsExtensions.nameJava)
            }
        }
        setBackgroundColor(background)
        loadContent()
    }

    private fun loadContent() {
        val current = document ?: return
        fun rgb(value: Int) = String.format(Locale.ROOT, "#%06x", value and 0xffffff)
        val web = current.mode == BookDetailIntroMode.Web
        val owner = ++contentGeneration
        contentJob?.cancel()
        val backgroundColor = background
        val textColor = text
        val accentColor = accent
        val showCollapsed = collapsed
        pageReady = false
        stopLoading()
        contentJob = contentScope.launch {
            val html =
                withContext(Dispatchers.IO) {
                    if (web) current.content
                    else
                        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
            html,body{padding:0;margin:0;color:${rgb(textColor)};background:${rgb(backgroundColor)};font-family:sans-serif;font-size:14px;line-height:1.4;overflow-wrap:anywhere}
            a{color:${rgb(accentColor)}} img{max-width:100%;height:auto} table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}
            td,th{border:1px solid ${rgb(textColor)};padding:6px} pre{white-space:pre-wrap}
            .dictionary-button{display:inline-block;padding:4px 8px;margin:4px 8px;border-radius:8px;color:${rgb(accentColor)}}
            .intro-body{${if(showCollapsed)"display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:4;max-height:5.6em;overflow:hidden;" else ""}}
            </style></head><body><div class="intro-body">${current.rich?.body.orEmpty()}</div></body></html>"""
                }
            ensureActive()
            if (released.get() || owner != contentGeneration) return@launch
            val base =
                if (web) bookUrl.takeIf { it.startsWith("http", true) }?.substringBefore(',')
                else "https://dictionary-content.invalid/intro/$owner/"
            loadedGeneration = owner
            loadedBase = base
            loadDataWithBaseURL(base, html, "text/html", "UTF-8", base)
        }
    }

    @Suppress("DEPRECATION")
    private fun reportSize() {
        if (released.get() || !pageReady || contentHeight <= 0) return
        val pixels = (contentHeight * scale).toInt().coerceIn(1, 1_000_000)
        if (document?.canCollapse == true && !fullMeasured) {
            val fourLines = (14f * 1.4f * 4f * fontScale * resources.displayMetrics.density).toInt()
            val hasOverflow = pixels > fourLines + 1
            overflow(hasOverflow)
            fullMeasured = true
            if (!expanded && hasOverflow) {
                collapsed = true
                loadContent()
                return
            }
        }
        if (pixels != lastPixels) {
            lastPixels = pixels
            size(pixels)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!released.get() && !reportPosted) {
            reportPosted = true
            post {
                reportPosted = false
                reportSize()
            }
        }
    }

    fun syncLifecycle(value: Boolean) {
        if (!released.get() && resumed != value) {
            resumed = value
            if (value) onResume() else onPause()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        images.cancel()
        contentScope.cancel()
        document = null
        source = null
        setOnLongClickListener(null)
        removeJavascriptInterface(WebJsExtensions.nameCache)
        removeJavascriptInterface(WebJsExtensions.nameSource)
        removeJavascriptInterface(WebJsExtensions.nameJava)
        webViewClient = WebViewClient()
        stopLoading()
        onPause()
        loadUrl("about:blank")
        removeAllViews()
        destroy()
    }
}
