package io.legado.app.ui.dict

import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.DictionaryImageData
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** The only platform view is the HTML result surface; no legacy dialog layout is embedded. */
@Composable
fun DictionaryResultWebView(
    document: DictionaryResultDocument,
    image: suspend (String) -> DictionaryImageData,
    action: (String) -> Boolean,
    onLink: (String) -> Unit,
    onImage: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    scrollY: Int = 0,
    onScroll: (Int) -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    val latestImage by rememberUpdatedState(image)
    val latestAction by rememberUpdatedState(action)
    val latestLink by rememberUpdatedState(onLink)
    val latestPhoto by rememberUpdatedState(onImage)
    val latestReady by rememberUpdatedState(onReady)
    val latestScrollY by rememberUpdatedState(scrollY)
    val latestScroll by rememberUpdatedState(onScroll)
    var native by remember { mutableStateOf<DictionaryHtmlView?>(null) }
    DisposableEffect(lifecycle, native) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_PAUSE ->
                    native?.syncLifecycle(
                        lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    )
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context ->
            DictionaryHtmlView(
                    context,
                    { latestImage(it) },
                    { latestAction(it) },
                    { latestLink(it) },
                    { latestPhoto(it) },
                    { latestReady() },
                    { latestScrollY },
                    { latestScroll(it) },
                )
                .also {
                    native = it
                    it.syncLifecycle(
                        lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    )
                }
        },
        modifier = modifier,
        onRelease = {
            native = null
            it.release()
        },
        update = {
            it.syncLifecycle(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            it.display(
                document,
                colors.surface.toArgb(),
                colors.onSurface.toArgb(),
                colors.primary.toArgb(),
                colors.onPrimary.toArgb(),
            )
        },
    )
}

internal class DictionaryHtmlView(
    context: Context,
    private val image: suspend (String) -> DictionaryImageData,
    private val action: (String) -> Boolean,
    private val link: (String) -> Unit,
    private val photo: (String) -> Unit,
    private val ready: () -> Unit,
    private val restoreScroll: () -> Int = { 0 },
    private val saveScroll: (Int) -> Unit = {},
) : WebView(context) {
    private val released = AtomicBoolean(false)
    val isReleased: Boolean
        get() = released.get()

    private val imageJob = SupervisorJob()
    @Volatile private var document: DictionaryResultDocument? = null
    private var rendered: Pair<DictionaryResultDocument, List<Int>>? = null
    private var pageReady = false
    private var lifecycleResumed: Boolean? = null
    val isLifecycleResumed: Boolean
        get() = lifecycleResumed == true

    init {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        isVerticalScrollBarEnabled = true
        webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!released.get() && url != "about:blank" && progress == 100) {
                        scrollTo(0, restoreScroll())
                        pageReady = true
                        saveScroll(scrollY)
                        ready()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest,
                ): Boolean = navigate(request.url.toString())

                @Deprecated("Platform callback for old Android")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url?.let(::navigate) ?: true

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val source = document?.image(request.url.toString())
                    if (source != null && !released.get()) {
                        return try {
                            val data = runBlocking(imageJob + Dispatchers.IO) { image(source) }
                            if (released.get()) emptyResponse()
                            else
                                WebResourceResponse(
                                    data.mime,
                                    null,
                                    ByteArrayInputStream(data.bytes),
                                )
                        } catch (_: Exception) {
                            emptyResponse()
                        }
                    }
                    // All image traffic uses the existing Legado Glide URL loader, including custom
                    // options.
                    return if (request.isForMainFrame) null else emptyResponse()
                }
            }
        setOnLongClickListener {
            val hit = hitTestResult
            val source = document?.image(hit?.extra.orEmpty())
            if (
                source != null &&
                    (hit?.type == HitTestResult.IMAGE_TYPE ||
                        hit?.type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                photo(source)
                true
            } else false // Native selection/copy menu remains available for text.
        }
    }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

    private fun navigate(url: String): Boolean {
        if (released.get()) return true
        if (url.startsWith(DICTIONARY_DOCUMENT_BASE + "#")) return false
        val host = Uri.parse(url).host
        if (host == "dictionary-action.invalid") {
            if (document?.action(url) != null) action(url)
            return true
        }
        if (host == "dictionary-image.invalid" || host == "dictionary-content.invalid") return true
        link(url)
        return true
    }

    fun display(
        next: DictionaryResultDocument,
        background: Int,
        text: Int,
        accent: Int,
        onAccent: Int,
    ) {
        if (released.get()) return
        val key = next to listOf(background, text, accent, onAccent)
        if (key == rendered) return
        rendered = key
        document = next
        pageReady = false
        fun rgb(value: Int) = String.format(Locale.ROOT, "#%06x", value and 0xffffff)
        setBackgroundColor(background)
        val html =
            """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
            body{padding:16px;margin:0;color:${rgb(text)};background:${rgb(background)};font-family:sans-serif;font-size:16px;overflow-wrap:anywhere}
            a{color:${rgb(accent)}} img{max-width:100%;height:auto} table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}
            td,th{border:1px solid ${rgb(text)};padding:6px} pre{white-space:pre-wrap} .dictionary-button{display:inline-block;padding:4px 8px;margin:4px 8px;border-radius:8px;background:${rgb(accent)};color:${rgb(onAccent)};text-decoration:none}
            </style></head><body>${next.body}</body></html>"""
        stopLoading()
        loadDataWithBaseURL(DICTIONARY_DOCUMENT_BASE, html, "text/html", "UTF-8", null)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (pageReady && !released.get()) saveScroll(top)
    }

    fun syncLifecycle(resumed: Boolean) {
        if (released.get() || lifecycleResumed == resumed) return
        lifecycleResumed = resumed
        if (resumed) onResume() else onPause()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        document = null
        imageJob.cancel()
        setOnLongClickListener(null)
        webViewClient = WebViewClient()
        stopLoading()
        loadUrl("about:blank")
        onPause()
        removeAllViews()
        destroy()
    }
}
