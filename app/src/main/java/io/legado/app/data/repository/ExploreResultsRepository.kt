package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.book.SearchBookShelfHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.mergeActiveShelfBook
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Keep data class ExploreResultsSource(val sourceUrl: String, val metadata: String)

data class ExploreResultsAddResult(val added: Int, val skipped: Int)

class ExploreResultsSourceMissing : IllegalStateException()

interface ExploreResultsRepository {
    suspend fun source(sourceUrl: String): ExploreResultsSource

    suspend fun categories(source: ExploreResultsSource): List<ExploreResultsCategory>

    suspend fun page(source: ExploreResultsSource, url: String, page: Int): List<ExploreResultsRow>

    suspend fun cache(rows: List<ExploreResultsRow>)

    fun membership(): Flow<Set<String>>

    suspend fun showCategories(): Boolean

    suspend fun showCategories(value: Boolean)

    suspend fun addToShelf(rows: List<ExploreResultsRow>): ExploreResultsAddResult
}

class AppExploreResultsRepository(
    private val database: AppDatabase = appDb,
    private val fetchPage: suspend (BookSource, String, Int) -> List<SearchBook> =
        { source, url, page ->
            WebBook.exploreBookAwait(source, url, page)
        },
) : ExploreResultsRepository {
    override suspend fun source(sourceUrl: String): ExploreResultsSource =
        withContext(Dispatchers.IO) {
            val source =
                database.bookSourceDao.getBookSource(sourceUrl)
                    ?: throw ExploreResultsSourceMissing()
            ExploreResultsSource(sourceUrl, GSON.toJson(source))
        }

    override suspend fun categories(source: ExploreResultsSource): List<ExploreResultsCategory> =
        withContext(Dispatchers.IO) {
            decodeSource(source)
                .exploreKinds()
                .filter {
                    it.type == ExploreKind.Type.url &&
                        !it.url.isNullOrBlank() &&
                        !it.title.startsWith("ERROR:")
                }
                .map { ExploreResultsCategory(it.title, it.url.orEmpty()) }
        }

    override suspend fun page(
        source: ExploreResultsSource,
        url: String,
        page: Int,
    ): List<ExploreResultsRow> =
        withContext(Dispatchers.IO) {
            val books = fetchPage(decodeSource(source), url, page)
            currentCoroutineContext().ensureActive()
            books.map(::exploreResultsRow)
        }

    override suspend fun cache(rows: List<ExploreResultsRow>) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            database.searchBookDao.insert(*rows.map(::decodeBook).toTypedArray())
            Unit
        }

    override fun membership(): Flow<Set<String>> =
        database.bookDao
            .flowAll()
            .map { books ->
                buildSet {
                    books
                        .filterNot { it.isNotShelf }
                        .forEach { book ->
                            add("${book.name}-${book.author}")
                            add(book.name)
                            add(book.bookUrl)
                        }
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun showCategories(): Boolean =
        withContext(Dispatchers.IO) {
            AppConfig.showExploreCategories
        }

    override suspend fun showCategories(value: Boolean) =
        withContext(Dispatchers.IO) {
            AppConfig.showExploreCategories = value
        }

    override suspend fun addToShelf(rows: List<ExploreResultsRow>): ExploreResultsAddResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val books = rows.map(::decodeBook)
            // The original transaction is idempotent by URL/name-author. After it accepts the
            // mutation, active readers and source callbacks must receive that same accepted set.
            withContext(NonCancellable) {
                val added = SearchBookShelfHelp.addLoadedBooksToShelf(books)
                val callbacks =
                    added.addedBooks.map { book ->
                        runCatching { database.bookSourceDao.getBookSource(book.origin) }
                            .getOrNull() to book
                    }
                withContext(Dispatchers.Main.immediate) {
                    val active =
                        added.addedBooks.flatMap(::syncActiveBook).distinctBy { it.bookUrl }
                    val states = active.map { ShelfState(it.bookUrl, it.type, it.order) }
                    if (states.isNotEmpty())
                        withContext(Dispatchers.IO) {
                            states.forEach {
                                database.bookDao.updateShelfState(it.bookUrl, it.type, it.order)
                            }
                        }
                    val activeByUrl = active.associateBy { it.bookUrl }
                    SourceCallBack.callBackBooks(
                        SourceCallBack.ADD_BOOK_SHELF,
                        callbacks.map { (source, book) ->
                            source to (activeByUrl[book.bookUrl] ?: book)
                        },
                    )
                }
                ExploreResultsAddResult(added.added, added.skipped)
            }
        }

    private fun decodeSource(source: ExploreResultsSource): BookSource =
        GSON.fromJsonObject<BookSource>(source.metadata).getOrThrow()

    private fun decodeBook(row: ExploreResultsRow): SearchBook =
        GSON.fromJsonObject<SearchBook>(row.metadata).getOrThrow()

    private fun syncActiveBook(book: Book): List<Book> {
        val changed = arrayListOf<Book>()
        mergeActiveShelfBook(ReadBook.book, book)?.let {
            ReadBook.book = it
            ReadBook.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) changed.add(it)
        }
        mergeActiveShelfBook(AudioPlay.book, book)?.let {
            AudioPlay.book = it
            AudioPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) changed.add(it)
        }
        mergeActiveShelfBook(ReadManga.book, book)?.let {
            ReadManga.book = it
            ReadManga.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) changed.add(it)
        }
        mergeActiveShelfBook(VideoPlay.book, book)?.let {
            VideoPlay.book = it
            VideoPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) changed.add(it)
        }
        return changed
    }

    private data class ShelfState(val bookUrl: String, val type: Int, val order: Int)
}

internal fun exploreResultsRow(book: SearchBook): ExploreResultsRow =
    ExploreResultsRow(
        key =
            MessageDigest.getInstance("SHA-256")
                .digest(book.bookUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) },
        bookUrl = book.bookUrl,
        name = book.name,
        author = book.author,
        origin = book.origin,
        coverUrl = book.coverUrl,
        intro = book.intro,
        latestChapter = book.latestChapterTitle,
        kinds = book.getKindList().toList(),
        metadata = GSON.toJson(book),
    )

internal fun exploreResultsInShelf(row: ExploreResultsRow, membership: Set<String>): Boolean {
    val nameAuthor = if (row.author.isNotBlank()) "${row.name}-${row.author}" else row.name
    return nameAuthor in membership || row.bookUrl in membership
}
