package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.readText
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Android/file/network operations run on the worker dispatcher chosen by the caller. */
class BookshelfTransferRepository(private val context: Context) {
    suspend fun addUrls(bookUrls: String, groupId: Long, onProgress: (Int) -> Unit): Int {
        var successCount = 0
        val hasBookUrlPattern: List<BookSourcePart> by lazy {
            appDb.bookSourceDao.hasBookUrlPattern
        }
        val urls = bookUrls.split("\n")
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            val bookUrl = url.trim()
            if (bookUrl.isEmpty()) continue
            val existedBook = appDb.bookDao.getBook(bookUrl)
            if (existedBook != null) {
                val mergedGroup = mergeBookGroupForUrlAdd(existedBook.group, groupId)
                if (mergedGroup != existedBook.group) {
                    existedBook.group = mergedGroup
                    existedBook.update()
                }
                successCount++
                continue
            }
            val baseUrl = NetworkUtils.getBaseUrl(bookUrl) ?: continue
            var source: BookSource? = null
            val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
            if (urlMatcher.find()) { //指定书源
                val origin = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                    bookUrl.substring(urlMatcher.end())
                ).getOrNull()?.getOrigin()
                try {
                    origin?.let {
                        appDb.bookSourceDao.getBookSource(it)?.let { bs ->
                            if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                source = bs
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (source == null) { //根据域名找书源
                source = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
            }
            if (source == null) {
                for (bookSource in hasBookUrlPattern) { //在所有启用的书源中查找
                    try {
                        val bs = bookSource.getBookSource()!!
                        if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                            source = bs
                            break
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            val bookSource = source ?: continue
            val book = Book(
                bookUrl = bookUrl,
                origin = bookSource.bookSourceUrl,
                originName = bookSource.bookSourceName
            )
            kotlin.runCatching {
                WebBook.getBookInfoAwait(bookSource, book)
            }.onFailure { currentCoroutineContext().ensureActive() }.onSuccess {
                val dbBook = appDb.bookDao.getBook(it.name, it.author)
                if (dbBook != null) {
                    val toc = WebBook.getChapterListAwait(bookSource, it).getOrThrow()
                    val migratedBook = migrateBookForUrlAdd(dbBook, it, toc, groupId)
                    replaceBookAfterSourceChange(dbBook, migratedBook, toc, clearActiveReader = false)
                } else {
                    it.group = mergeBookGroupForUrlAdd(it.group, groupId)
                    it.order = appDb.bookDao.minOrder - 1
                    it.savePreservingCustomCoverUrl()
                }
                successCount++
                onProgress(successCount)
            }
        }
        return successCount
    }
    suspend fun exportBooks(books: List<Book>?): File {
        requireNotNull(books) { "书籍不能为空" }
        val file = File.createTempFile("bookshelf-", ".json", context.cacheDir)
        try { FileOutputStream(file).use { writeBookshelfExport(books, OutputStreamWriter(it, "UTF-8")) } }
        catch (error: Throwable) { file.delete(); throw error }
        return file
    }
    suspend fun importFile(uri: String, groupId: Long) { importBooks(Uri.parse(uri).readText(context), groupId) }
    suspend fun importBooks(str: String, groupId: Long) {
        val text = str.trim()
        when {
            text.isAbsUrl() -> importBooks(okHttpClient.newCallResponseBody { url(text) }.decompressed().text(), groupId)
            text.isJsonArray() -> importBookshelfJson(text, groupId)
            else -> throw NoStackTraceException("格式不对")
        }
    }
}

/** The file picker and system sharing use the same enabled-source matching. */
internal suspend fun importBookshelfJson(json: String, groupId: Long) = coroutineScope {
    val books = parseBookshelfImport(json)
    val sources = appDb.bookSourceDao.allEnabledPart
    val semaphore = Semaphore(AppConfig.threadCount)
    val failures = books.map { (name, author) ->
        async {
            runCatching {
                semaphore.withPermit {
                    if (appDb.bookDao.has(name, author)) return@withPermit
                    val book = sources.firstNotNullOfOrNull { part ->
                        part.getBookSource()?.let { WebBook.preciseSearchAwait(it, name, author).getOrNull() }
                    } ?: throw NoStackTraceException("没有搜索到<$name>$author")
                    if (groupId > 0) book.group = groupId
                    book.savePreservingCustomCoverUrl()
                }
            }.onFailure { currentCoroutineContext().ensureActive() }.exceptionOrNull()
        }
    }.awaitAll().filterNotNull()
    if (failures.isNotEmpty()) {
        throw NoStackTraceException(failures.joinToString("\n") { it.localizedMessage.orEmpty() })
    }
    Unit
}

internal fun parseBookshelfImport(json: String): List<Pair<String, String>> {
    return GSON.fromJsonArray<JsonObject>(json).getOrThrow().map { book ->
        val name = book.get("name")
        val author = book.get("author")
        require(name != null && name.isJsonPrimitive && name.asJsonPrimitive.isString)
        require(author == null || author.isJsonNull ||
            author.isJsonPrimitive && author.asJsonPrimitive.isString)
        name.asString.also { require(it.isNotBlank()) } to
            author?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.distinct()
}

internal suspend fun writeBookshelfExport(books: List<Book>, output: java.io.Writer) {
    JsonWriter(output).use { writer ->
        writer.setIndent("  "); writer.beginArray()
        books.forEach { book ->
            currentCoroutineContext().ensureActive()
            val values = hashMapOf("name" to book.name, "author" to book.author, "intro" to book.getDisplayIntro())
            GSON.toJson(values, values::class.java, writer)
        }
        writer.endArray()
    }
}
