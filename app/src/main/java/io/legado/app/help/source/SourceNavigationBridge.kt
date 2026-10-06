package io.legado.app.help.source

import android.webkit.JavascriptInterface
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.VideoPlay
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Carries the owning task across WebView's separate Java bridge thread. */
internal fun BaseSource.withSourceNavigationContext(context: CoroutineContext): BaseSource {
    val original = this
    return object : BaseSource by original {
        override fun getSourceNavigationContext(): CoroutineContext = context

        private fun allowed(): Boolean {
            context.ensureActive()
            return !shouldSuppressSourceNavigation(AppConfig.blockSourceNavigation, context)
        }

        override fun evalJS(
            jsStr: String,
            bindingsConfig: MutableMap<String, Any?>.() -> Unit,
        ): Any? {
            context.ensureActive()
            return super<BaseSource>.evalJS(jsStr, bindingsConfig)
        }

        @JavascriptInterface
        override fun login() {
            context.ensureActive()
            super<BaseSource>.login()
        }

        @JavascriptInterface
        override fun openVideoPlayer(url: String, title: String) =
            openVideoPlayer(url, title, VideoPlay.defaultFloatWindow)

        @JavascriptInterface
        override fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
            if (allowed()) original.openVideoPlayer(url, title, isFloat)
        }

        @JavascriptInterface override fun openUrl(url: String) = openUrl(url, null)

        @JavascriptInterface
        override fun openUrl(url: String, mimeType: String?) {
            if (allowed()) original.openUrl(url, mimeType)
        }
    }
}
