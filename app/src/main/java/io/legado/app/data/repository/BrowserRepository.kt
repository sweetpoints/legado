package io.legado.app.data.repository

import io.legado.app.model.browser.*
import kotlinx.coroutines.*

internal class BrowserSessionClosedException : IllegalStateException("网页会话已关闭")
internal interface BrowserDataStore {
    suspend fun prepare(request: BrowserRequest): BrowserPage
    suspend fun refetch(page: BrowserPage): BrowserVerification
    suspend fun captured(htmlJson: String, url: String): BrowserVerification
    suspend fun saveImage(data: String, directory: String)
    suspend fun imageDirectory(): String?
    suspend fun imageDirectory(value: String)
    suspend fun forgetImageDirectory(expected: String)
    suspend fun disableSource(origin: String, type: Int)
    suspend fun deleteSource(origin: String, type: Int)
    suspend fun webCookies(url: String): BrowserWebCookies?
    suspend fun cookie(url: String, value: String?)
}
internal interface BrowserSessionStore {
    suspend fun read(session: String): BrowserSession?
    suspend fun create(session: String, seed: BrowserSession): BrowserSession
    suspend fun write(session: String, snapshot: BrowserSession)
    suspend fun release(session: String)
}
internal interface BrowserRepository : BrowserDataStore, BrowserSessionStore
internal class DefaultBrowserRepository(private val data: BrowserDataStore, private val sessions: BrowserSessionStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BrowserRepository {
    override suspend fun prepare(request: BrowserRequest) = withContext(io) { data.prepare(request).also { currentCoroutineContext().ensureActive() } }
    override suspend fun refetch(page: BrowserPage) = withContext(io) { data.refetch(page).also { currentCoroutineContext().ensureActive() } }
    override suspend fun captured(htmlJson: String, url: String) = withContext(io) { data.captured(htmlJson, url) }
    override suspend fun saveImage(data: String, directory: String) = withContext(io) { this@DefaultBrowserRepository.data.saveImage(data, directory) }
    override suspend fun imageDirectory() = withContext(io) { data.imageDirectory() }
    override suspend fun imageDirectory(value: String) = withContext(io) { data.imageDirectory(value) }
    override suspend fun forgetImageDirectory(expected: String) = withContext(io) { data.forgetImageDirectory(expected) }
    override suspend fun disableSource(origin: String, type: Int) = withContext(io) { data.disableSource(origin, type) }
    override suspend fun deleteSource(origin: String, type: Int) = withContext(io) { data.deleteSource(origin, type) }
    override suspend fun webCookies(url: String) = withContext(io) { data.webCookies(url).also { currentCoroutineContext().ensureActive() } }
    override suspend fun cookie(url: String, value: String?) = withContext(io) { data.cookie(url, value) }
    override suspend fun read(session: String) = withContext(io) { sessions.read(session) }
    override suspend fun create(session: String, seed: BrowserSession) = withContext(io + NonCancellable) { sessions.create(session, seed) }
    override suspend fun write(session: String, snapshot: BrowserSession) = withContext(io + NonCancellable) { sessions.write(session, snapshot) }
    override suspend fun release(session: String) = withContext(io + NonCancellable) { sessions.release(session) }
}
