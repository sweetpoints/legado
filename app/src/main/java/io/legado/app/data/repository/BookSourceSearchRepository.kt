package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Shared immutable search projections and options are data types, independent of either dialog. */
internal interface BookSourceSearchStore : ChapterSourceSearchStore {
    suspend fun sourceExists(origin: String): Boolean
}

internal class AppBookSourceSearchStore(
    private val delegate: AppChapterSourceSearchStore,
    private val database: AppDatabase = appDb,
) : BookSourceSearchStore, ChapterSourceSearchStore by delegate {
    override suspend fun sourceExists(origin: String) =
        database.bookSourceDao.getBookSourcePart(origin) != null

    override suspend fun sourceName(origin: String) =
        database.bookSourceDao.getBookSourcePart(origin)?.bookSourceName ?: origin
}

internal interface BookSourceSearchRepository : ChapterSourceSearchRepository {
    fun search(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
        origin: String?,
    ): Flow<ChapterSourceSearchUpdate>
}

internal class DefaultBookSourceSearchRepository(
    private val store: BookSourceSearchStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 60000,
    private val concurrency: Int? = null,
) : BookSourceSearchRepository {
    private fun delegate(target: ChapterSourceSearchStore = store) =
        DefaultChapterSourceSearchRepository(
            target,
            io,
            timeoutMillis,
            concurrency,
            pinCurrentSource = false,
        )

    override suspend fun cached(request: ChapterSourceSearchRequest): ChapterSourceSearchUpdate {
        val cached = delegate().cached(request)
        return cached.copy(
            rows = project(request, cached.allRows),
            referenceWordCount =
                cached.referenceWordCount ?: measuredReference(request, cached.allRows),
        )
    }

    private fun measuredReference(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
    ) = rows.firstOrNull { it.id == request.currentBookUrl }?.wordCount?.takeIf { it > 0 }

    override suspend fun project(
        request: ChapterSourceSearchRequest,
        rows: List<ChapterSourceSearchRow>,
    ) =
        withContext(io) {
            val reference = store.reference(request) ?: measuredReference(request, rows)
            val projection =
                object : ChapterSourceSearchStore by store {
                    override suspend fun reference(request: ChapterSourceSearchRequest) = reference
                }
            delegate(projection).project(request, rows)
        }

    override fun measure(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
        missingOnly: Boolean,
    ) = delegate().measure(request, previous, missingOnly)

    override fun search(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
    ) = search(request, previous, null)

    override fun search(
        request: ChapterSourceSearchRequest,
        previous: List<ChapterSourceSearchRow>,
        origin: String?,
    ): Flow<ChapterSourceSearchUpdate> {
        if (origin == null) return delegate().search(request, previous)
        val retained = previous.filter { it.origin != origin }
        val single =
            object : ChapterSourceSearchStore by store {
                override suspend fun sources(
                    request: ChapterSourceSearchRequest
                ): ChapterSourceSearchSources {
                    check(store.sourceExists(origin)) { "书源不存在" }
                    return ChapterSourceSearchSources(listOf(origin), request.group)
                }

                override suspend fun reset(previous: List<ChapterSourceSearchRow>) {
                    store.reset(previous.filter { it.origin == origin })
                }
            }
        return delegate(single).search(request, previous).map { update ->
            val rows = (retained + update.allRows).associateBy { it.id }.values.toList()
            update.copy(
                rows =
                    project(request, rows).filter {
                        request.query.isEmpty() || it.name.contains(request.query)
                    },
                allRows = rows,
                referenceWordCount = update.referenceWordCount ?: measuredReference(request, rows),
            )
        }
    }
}
