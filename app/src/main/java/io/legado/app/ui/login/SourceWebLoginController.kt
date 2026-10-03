package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.repository.AppSourceLoginCookieRepository
import io.legado.app.data.repository.SourceLoginCookieRepository
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SourceWebLoginState(
    val progress: Int = 0,
    val checking: Boolean = false,
    val completed: Boolean = false,
    val external: String? = null,
)

/** The platform browser and cookie protocol are confined to the native content owner. */
@SuppressLint("SetJavaScriptEnabled")
internal class SourceWebLoginController(
    context: Context,
    private val source: BaseSource,
    private val headers: Map<String, String>,
    private val cookieRepository: SourceLoginCookieRepository = AppSourceLoginCookieRepository(),
) {
    private val mutable = MutableStateFlow(SourceWebLoginState())
    val state = mutable.asStateFlow()
    private val lease = WebViewPool.acquire(context)
    val webView = lease.realWebView
    private var released = false
    private var checkEpoch = 0L

    private data class Capture(val cookie: String?, val check: Long?)

    private val writes = Channel<Capture>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = scope.launch {
        for (capture in writes) {
            try {
                cookieRepository.store(source.getKey(), capture.cookie)
                currentCoroutineContext().ensureActive()
                if (
                    !released &&
                        capture.check == checkEpoch &&
                        capture.check != null &&
                        mutable.value.checking
                )
                    mutable.value = mutable.value.copy(completed = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!released && capture.check == checkEpoch && capture.check != null)
                    mutable.value = mutable.value.copy(checking = false)
                AppLog.put("保存Cookie失败\n$error", error)
            }
        }
    }

    init {
        val request = headers.toWebViewRequestConfig(AppConfig.userAgent)
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = request.userAgent
        }
        val cookies = CookieManager.getInstance()
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (!released) writes.trySend(Capture(cookies.getCookie(url), null))
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!released) {
                        writes.trySend(
                            Capture(
                                cookies.getCookie(url),
                                if (mutable.value.checking) checkEpoch else null,
                            )
                        )
                    }
                    super.onPageFinished(view, url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    external(request.url)

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) =
                    external(url.toUri())

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.proceed()
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!released) mutable.value = mutable.value.copy(progress = newProgress)
                    super.onProgressChanged(view, newProgress)
                }
            }
        load()
    }

    private fun external(uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return false
        if (!released) mutable.value = mutable.value.copy(external = uri.toString())
        return true
    }

    fun consumeExternal() {
        mutable.value = mutable.value.copy(external = null)
    }

    fun check() {
        if (released || mutable.value.checking) return
        checkEpoch++
        mutable.value = mutable.value.copy(checking = true)
        load()
    }

    private fun load() {
        val url = source.loginUrl ?: return
        val request = headers.toWebViewRequestConfig(AppConfig.userAgent)
        webView.settings.userAgentString = request.userAgent
        webView.loadUrl(
            NetworkUtils.getAbsoluteURL(source.getKey(), url),
            request.additionalHeaders,
        )
    }

    fun resume() {
        if (!released) webView.onResume()
    }

    fun pause() {
        if (!released) webView.onPause()
    }

    fun release() {
        if (released) return
        released = true
        writes.close()
        writer.cancel()
        scope.cancel()
        WebViewPool.release(lease)
    }
}
