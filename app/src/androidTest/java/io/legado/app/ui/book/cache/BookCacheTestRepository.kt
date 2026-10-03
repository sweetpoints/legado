package io.legado.app.ui.book.cache

import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class BookCacheTestRepository : BookCacheRepository {
    val rows = MutableStateFlow(listOf(BookCacheItem("remote", "Remote", "Author", false, 20, 4, 19), BookCacheItem("local", "Local", "Writer", true, 8, 0, 7)))
    var prefs = BookCachePreferences()
    var path: String? = null
    var nextTicket = 0
    val tickets = mutableMapOf<String, List<String>>()
    val results = mutableMapOf<String, BookCacheFolderResult>()
        val sections = mutableMapOf<String, BookCacheSectionDraft>()
    val exports = mutableListOf<BookCacheExport>()
    val downloads = mutableListOf<Boolean>()
    var stageGate: CompletableDeferred<Unit>? = null
    override fun books(group: Long): Flow<List<BookCacheItem>> = rows
    override fun groups() = flowOf(listOf(BookCacheGroup(1, "One"), BookCacheGroup(2, "Two")))
    override suspend fun scan(key: String) = BookCacheScan(setOf("chapter"), 20)
    override suspend fun runtime() = BookCacheRuntime(false, emptySet(), emptyMap(), emptyMap())
    override suspend fun preferences() = prefs
    override suspend fun preferences(value: BookCachePreferences, fields: Set<BookCachePreference>) { prefs = value }
    override suspend fun cachedPath() = path
    override suspend fun rememberPath(path: String) { this.path = path }
    override suspend fun writable(path: String) = true
    override suspend fun download(keys: List<String>, afterCurrent: Boolean) { downloads += afterCurrent }
    override suspend fun stopDownloads() = Unit
    override suspend fun toggleDownload(key: String) = Unit
    override suspend fun export(request: BookCacheExport) { exports += request }
    override suspend fun stage(keys: List<String>): String { withContext(NonCancellable) { stageGate?.await() }; val id = "ticket-${nextTicket++}"; tickets[id] = keys.toList(); return id }
    override suspend fun staged(ticket: String) = tickets.getValue(ticket)
    override suspend fun readSection(ticket: String) = sections[ticket]
    override suspend fun writeSection(ticket: String, draft: BookCacheSectionDraft) { if ((sections[ticket]?.revision ?: -1) < draft.revision) sections[ticket] = draft }
    override suspend fun folderResult(ticket: String) = results[ticket]
        override suspend fun folderResult(ticket: String, result: BookCacheFolderResult): Boolean { if (ticket !in tickets || ticket in results) return false; results[ticket] = result; return true }
        override suspend fun release(ticket: String) { tickets.remove(ticket); sections.remove(ticket); results.remove(ticket) }
    override suspend fun episodeName(key: String, script: String) = "$script.epub"
    override suspend fun validEpisodeName(script: String) = script.isNotBlank()
}
