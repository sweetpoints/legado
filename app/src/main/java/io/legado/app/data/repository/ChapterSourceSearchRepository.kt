package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import io.legado.app.model.book.ChangeSourceResultOptions
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class ChapterSourceSearchRequest(
    val name: String,
    val author: String,
    val originalBookJson: String? = null,
    val fromReader: Boolean = true,
    val query: String = "",
    val group: String = "",
    val checkAuthor: Boolean = true,
    val loadInfo: Boolean = false,
    val loadToc: Boolean = false,
    val loadWordCount: Boolean = false,
    val sortResponseTime: Boolean = false,
    val filterMode: Int = 0,
    val minimum: Int = 0,
    val maximum: Int = 0,
    val currentBookUrl: String? = null,
)

/**
 * Full JSON is immutable private-session data; Bundle/arguments contain only the session identity.
 */
internal data class ChapterSourceSearchRow(
    val id: String,
    val origin: String,
    val originName: String,
    val name: String,
    val author: String,
    val latest: String,
    val wordCountText: String?,
    val wordCount: Int,
    val responseTime: Int,
    val order: Int,
    val score: Int,
    val type: Int,
    val json: String,
)

internal data class ChapterSourceSearchSources(val ids: List<String>, val effectiveGroup: String)

internal data class ChapterSourceSearchUpdate(
    val rows: List<ChapterSourceSearchRow>,
    val running: Boolean,
    val completed: Int = 0,
    val total: Int = 0,
    val sourceName: String = "",
    val effectiveGroup: String = "",
    val referenceWordCount: Int? = null,
    val allRows: List<ChapterSourceSearchRow> = rows,
)

internal interface ChapterSourceSearchStore {
    suspend fun cached(request: ChapterSourceSearchRequest): List<ChapterSourceSearchRow>

    suspend fun sources(request: ChapterSourceSearchRequest): ChapterSourceSearchSources

    suspend fun reset(previous: List<ChapterSourceSearchRow>)

    suspend fun search(
        request: ChapterSourceSearchRequest,
        source: String,
    ): List<ChapterSourceSearchRow>

    fun searchResults(
        request: ChapterSourceSearchRequest,
        source: String,
    ): Flow<ChapterSourceSearchRow> = flow {
        search(request, source).forEach { emit(it) }
    }

    suspend fun measure(
        request: ChapterSourceSearchRequest,
        row: ChapterSourceSearchRow,
    ): ChapterSourceSearchRow

    suspend fun persist(row: ChapterSourceSearchRow)

    suspend fun reference(request: ChapterSourceSearchRequest): Int?

    suspend fun sourceName(origin: String): String = origin

    fun sourceScore(origin: String): Int

    fun threadCount(): Int

    fun log(error: Throwable)
}

internal interface ChapterSourceSearchRepository {
    suspend fun cached(request: ChapterSourceSearchRequest): ChapterSourceSearchUpdate

    fun search(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
    ): Flow<ChapterSourceSearchUpdate>

    fun measure(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
        missingOnly: Boolean,
    ): Flow<ChapterSourceSearchUpdate>

    suspend fun project(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
    ): List<ChapterSourceSearchRow>
}

internal class DefaultChapterSourceSearchRepository(
    private val store: ChapterSourceSearchStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 60000,
    private val concurrency: Int? = null,
    private val pinCurrentSource: Boolean = true,
) : ChapterSourceSearchRepository {
    override suspend fun cached(request: ChapterSourceSearchRequest) =
        withContext(io) {
            val rows = store.cached(request)
            val reference = store.reference(request)
            ChapterSourceSearchUpdate(
                order(request, rows, reference),
                false,
                effectiveGroup = request.group,
                referenceWordCount = reference,
                allRows = rows,
            )
        }

    override suspend fun project(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
    ) =
        withContext(io) {
            order(request, rows, store.reference(request))
        }

    override fun search(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
    ): Flow<ChapterSourceSearchUpdate> = channelFlow {
        store.reset(previous)
        val sources = store.sources(request)
        val reference = store.reference(request)
        val records = linkedMapOf<String, ChapterSourceSearchRow>()
        val lock = Mutex()
        var completed = 0
        send(
            ChapterSourceSearchUpdate(
                emptyList(),
                true,
                total = sources.ids.size,
                effectiveGroup = sources.effectiveGroup,
                referenceWordCount = reference,
            )
        )
        val semaphore = Semaphore((concurrency ?: store.threadCount()).coerceIn(1, 9))
        coroutineScope {
            sources.ids.forEach { source ->
                launch {
                    semaphore.withPermit {
                        var sourceName = source
                        try {
                            withTimeout(timeoutMillis) {
                                sourceName = store.sourceName(source)
                                store.searchResults(request, source).collect { row ->
                                    currentCoroutineContext().ensureActive()
                                    store.persist(row)
                                    currentCoroutineContext().ensureActive()
                                    lock.withLock {
                                        records[row.id] = row
                                        val visible =
                                            records.values.filter {
                                                request.query.isEmpty() ||
                                                    it.name.contains(request.query)
                                            }
                                        val actualReference =
                                            reference
                                                ?: records[request.currentBookUrl]
                                                    ?.wordCount
                                                    ?.takeIf { it > 0 }
                                        send(
                                            ChapterSourceSearchUpdate(
                                                order(request, visible, actualReference),
                                                true,
                                                completed,
                                                sources.ids.size,
                                                sourceName,
                                                sources.effectiveGroup,
                                                actualReference,
                                                records.values.toList(),
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            /* Preserve rows emitted before the timeout. */
                        } catch (canceled: CancellationException) {
                            throw canceled
                        } catch (error: Exception) {
                            store.log(error)
                        }
                        currentCoroutineContext().ensureActive()
                        lock.withLock {
                            completed++
                            val visible =
                                records.values.filter {
                                    request.query.isEmpty() || it.name.contains(request.query)
                                }
                            val actualReference =
                                reference
                                    ?: records[request.currentBookUrl]?.wordCount?.takeIf { it > 0 }
                            send(
                                ChapterSourceSearchUpdate(
                                    order(request, visible, actualReference),
                                    true,
                                    completed,
                                    sources.ids.size,
                                    sourceName,
                                    sources.effectiveGroup,
                                    actualReference,
                                    records.values.toList(),
                                )
                            )
                        }
                    }
                }
            }
        }
        val actualReference =
            reference ?: records[request.currentBookUrl]?.wordCount?.takeIf { it > 0 }
        send(
            ChapterSourceSearchUpdate(
                order(
                    request,
                    records.values.filter {
                        request.query.isEmpty() || it.name.contains(request.query)
                    },
                    actualReference,
                ),
                false,
                completed,
                sources.ids.size,
                effectiveGroup = sources.effectiveGroup,
                referenceWordCount = actualReference,
                allRows = records.values.toList(),
            )
        )
    }
        .flowOn(io)

    override fun measure(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
        missingOnly: Boolean,
    ): Flow<ChapterSourceSearchUpdate> = channelFlow {
        val records = previous.associateByTo(linkedMapOf()) { it.id }
        val reference = store.reference(request)
        val targets = if (missingOnly) previous.filter { it.wordCountText == null } else previous
        val lock = Mutex()
        var completed = 0
        send(
            ChapterSourceSearchUpdate(
                order(request, records.values.toList(), reference),
                true,
                total = targets.size,
                referenceWordCount = reference,
                allRows = previous.toList(),
            )
        )
        val semaphore = Semaphore((concurrency ?: store.threadCount()).coerceIn(1, 9))
        coroutineScope {
            targets.forEach { row ->
                launch {
                    semaphore.withPermit {
                        val result =
                            try {
                                withTimeout(timeoutMillis) {
                                    store.measure(request, row).also {
                                        currentCoroutineContext().ensureActive()
                                        store.persist(it)
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                null
                            } catch (canceled: CancellationException) {
                                throw canceled
                            } catch (error: Exception) {
                                store.log(error)
                                null
                            }
                        currentCoroutineContext().ensureActive()
                        lock.withLock {
                            result?.let { records[it.id] = it }
                            completed++
                            val actualReference =
                                reference
                                    ?: records[request.currentBookUrl]?.wordCount?.takeIf { it > 0 }
                            send(
                                ChapterSourceSearchUpdate(
                                    order(request, records.values.toList(), actualReference),
                                    true,
                                    completed,
                                    targets.size,
                                    row.originName,
                                    request.group,
                                    actualReference,
                                    records.values.toList(),
                                )
                            )
                        }
                    }
                }
            }
        }
        val actualReference =
            reference ?: records[request.currentBookUrl]?.wordCount?.takeIf { it > 0 }
        send(
            ChapterSourceSearchUpdate(
                order(request, records.values.toList(), actualReference),
                false,
                completed,
                targets.size,
                effectiveGroup = request.group,
                referenceWordCount = actualReference,
                allRows = records.values.toList(),
            )
        )
    }
        .flowOn(io)

    private fun order(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
        reference: Int?,
    ): List<ChapterSourceSearchRow> {
        val byId = rows.associateBy { it.id }
        val books = rows.map { GSON.fromJson(it.json, SearchBook::class.java) }
        val base =
            compareByDescending<SearchBook> { byId[it.bookUrl]?.score ?: 0 }
                .thenByDescending { store.sourceScore(it.origin) }
        val default = base.thenBy { it.originOrder }
        val measured =
            base
                .thenByDescending { it.chapterWordCount > 1000 }
                .thenByDescending {
                    it.chapterWordCountText?.let { text ->
                        "^\\[(\\d+)]".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: -1
                }
                .thenByDescending { it.chapterWordCount }
                .thenBy { it.originOrder }
        val filter =
            if (request.loadWordCount) request.filterMode else ChangeSourceResultOptions.FILTER_OFF
        val comparator =
            when {
                request.sortResponseTime ->
                    ChangeSourceResultOptions.responseTimeComparator(default)
                filter != ChangeSourceResultOptions.FILTER_OFF ->
                    ChangeSourceResultOptions.measuredFirstComparator(measured)
                request.loadWordCount -> measured
                else -> default
            }
        return ChangeSourceResultOptions.apply(
                books,
                filter,
                request.minimum,
                request.maximum,
                reference,
                comparator,
                request.currentBookUrl.takeIf { pinCurrentSource },
            )
            .mapNotNull { byId[it.bookUrl] }
    }
}
