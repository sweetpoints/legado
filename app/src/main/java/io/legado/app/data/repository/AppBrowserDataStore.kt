package io.legado.app.data.repository

import android.util.Base64
import android.webkit.URLUtil
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.browser.*
import io.legado.app.utils.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.text.StringEscapeUtils
import java.util.Date

/** No WebView/Activity is retained here; native requests and Javascript evaluation stay in the host. */
internal class AppBrowserDataStore : BrowserDataStore {
    override suspend fun prepare(request: BrowserRequest): BrowserPage {
        require(request.url.isNotEmpty()) { "url不能为空" }
        val source = SourceHelp.getSource(request.sourceOrigin, request.sourceType)
        val analyze = AnalyzeUrl(request.url, source = source, coroutineContext = currentCoroutineContext())
        var html = request.html?.let { injectBrowserScript(it, WebJsExtensions.JS_INJECTION2) }
        if (analyze.isPost()) html = analyze.getStrResponseAwait(useWebView = false).body
        currentCoroutineContext().ensureActive()
        return BrowserPage(request, analyze.url, html, request.html != null, analyze.headerMap.toMap(), AppConfig.userAgent,
            source?.let { BrowserSource(request.sourceType, GSON.toJson(it)) })
    }
    override suspend fun refetch(page: BrowserPage): BrowserVerification {
        // Preserve the legacy book-source lookup and reuse of an existing HTML response.
        val html = page.html ?: AnalyzeUrl(page.request.url, headerMapF = page.headers,
            source = appDb.bookSourceDao.getBookSource(page.request.sourceOrigin), coroutineContext = currentCoroutineContext())
            .getStrResponseAwait(useWebView = false).body.orEmpty()
        currentCoroutineContext().ensureActive()
        return BrowserVerification(html, page.baseUrl)
    }
    override suspend fun captured(htmlJson: String, url: String) = BrowserVerification(StringEscapeUtils.unescapeJson(htmlJson).trim('"'), url)
    override suspend fun saveImage(data: String, directory: String) {
        val bytes = if (URLUtil.isValidUrl(data)) okHttpClient.newCallResponseBody { url(data) }.bytes()
            else Base64.decode(data.split(',').toTypedArray()[1], Base64.DEFAULT)
        currentCoroutineContext().ensureActive()
        val name = synchronized(AppConst.fileNameFormat) { AppConst.fileNameFormat.format(Date(System.currentTimeMillis())) + ".jpg" }
        FileDoc.fromDir(directory).createFileIfNotExist(name).openOutputStream().getOrThrow().use { it.write(bytes) }
    }
    override suspend fun imageDirectory() = synchronized(imageDirectoryLock) { ACache.get().getAsString(AppConst.imagePathKey) }
    override suspend fun imageDirectory(value: String) { synchronized(imageDirectoryLock) { ACache.get().put(AppConst.imagePathKey, value) } }
    override suspend fun forgetImageDirectory(expected: String) { synchronized(imageDirectoryLock) {
        if (ACache.get().getAsString(AppConst.imagePathKey) == expected) ACache.get().remove(AppConst.imagePathKey)
    } }
    override suspend fun disableSource(origin: String, type: Int) { SourceHelp.enableSource(origin, type, false) }
    override suspend fun deleteSource(origin: String, type: Int) { SourceHelp.deleteSource(origin, type) }
    override suspend fun cookie(url: String, value: String?) { CookieStore.setCookie(url, value) }
    private companion object { val imageDirectoryLock = Any() }
}
