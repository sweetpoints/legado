package io.legado.app.model.webBook

import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.help.source.getBookType
import io.legado.app.model.BatchContentContext
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.model.Debug
import io.legado.app.model.jsSource.JsSourceMarshaller
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils.wordCountFormat
import io.legado.app.utils.isTrue
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import splitties.init.appCtx

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /** Export the live entity variables together with the immutable script snapshot. */
    private fun scriptSnapshot(value: RuleDataInterface): Map<String, Any?> =
        DartSourceEngine.jsonObject(value) + ("variable" to GSON.toJson(value.variableMap))

    private fun usesLegacyDartFields(source: BookSource): Boolean {
        val definition =
            source.bookSourceComment
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("@source:v1 ") }
                ?.removePrefix("@source:v1 ") ?: return true
        val metadata = GSON.fromJson(definition, JsonObject::class.java).getAsJsonObject("metadata")
        if (metadata?.get("legacyOriginal")?.isJsonObject == true) return true
        val legacy = metadata?.get("legacy")
        return legacy != null &&
            legacy.isJsonPrimitive &&
            legacy.asJsonPrimitive.isBoolean &&
            legacy.asBoolean
    }

    private fun canRenameDartBook(source: BookSource): Boolean {
        if (source.isJsSource()) return true
        if (!usesLegacyDartFields(source)) return true
        val definition =
            source.bookSourceComment
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("@source:v1 ") }
                ?.removePrefix("@source:v1 ")
        if (definition != null) {
            val original =
                GSON.fromJson(definition, JsonObject::class.java)
                    .getAsJsonObject("metadata")
                    ?.get("legacyOriginal")
            if (original?.isJsonObject == true) {
                val permission =
                    original.asJsonObject.getAsJsonObject("ruleBookInfo")?.get("canReName")
                return permission?.isJsonPrimitive == true && !permission.asString.isBlank()
            }
        }
        return !source.getBookInfoRule().canReName.isNullOrBlank()
    }

    private fun normalizeDartBookFields(
        source: BookSource,
        row: Map<String, Any?>,
    ): Map<String, Any?> {
        if (!usesLegacyDartFields(source)) return row
        return row.toMutableMap().apply {
            (row["name"] as? String)?.let { this["name"] = BookHelp.formatBookName(it) }
            (row["author"] as? String)?.let { this["author"] = BookHelp.formatBookAuthor(it) }
            (row["wordCount"] as? String)?.let { this["wordCount"] = wordCountFormat(it) }
            (row["kind"] as? String)?.let { this["kind"] = it.replace("\n", ",") }
        }
    }

    /** 搜索 */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBook>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String, kind: String?) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null,
    ): ArrayList<SearchBook> =
        withContext(SuppressSourceNavigation) {
            val rows =
                DartSourceEngine.execute(
                    bookSource,
                    "search",
                    mapOf("key" to key, "page" to (page ?: 1)),
                )
            return@withContext ArrayList(
                rows
                    .map { row ->
                        GSON.fromJson(
                                GSON.toJson(normalizeDartBookFields(bookSource, row)),
                                SearchBook::class.java,
                            )
                            .apply {
                                origin = bookSource.bookSourceUrl
                                originName = bookSource.bookSourceName
                                type = bookSource.getBookType()
                            }
                    }
                    .filter { filter?.invoke(it.name, it.author, it.kind) != false }
            )
        }

    /** 发现 */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
    ): Coroutine<List<SearchBook>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        return ArrayList(
            DartSourceEngine.execute(
                    bookSource,
                    "explore",
                    mapOf("url" to url, "exploreUrl" to url, "page" to (page ?: 1)),
                )
                .map {
                    GSON.fromJson(
                            GSON.toJson(normalizeDartBookFields(bookSource, it)),
                            SearchBook::class.java,
                        )
                        .apply {
                            origin = bookSource.bookSourceUrl
                            originName = bookSource.bookSourceName
                            type = bookSource.getBookType()
                        }
                }
        )
    }

    /** 书籍信息 */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())

        val fields =
            normalizeDartBookFields(
                bookSource,
                DartSourceEngine.execute(
                        bookSource,
                        "info",
                        scriptSnapshot(book) +
                            mapOf("book" to scriptSnapshot(book)),
                    )
                    .single(),
            )
        val allowRename = canReName && canRenameDartBook(bookSource)
        (fields["name"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                if (allowRename || book.name.isEmpty()) book.name = it
            }
        (fields["author"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                if (allowRename || book.author.isEmpty()) book.author = it
            }
        (fields["tocUrl"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.tocUrl = it }
        (fields["coverUrl"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.coverUrl = it }
        (fields["intro"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.intro = it }
        (fields["kind"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.kind = it }
        (fields["wordCount"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.wordCount = it }
        (fields["latestChapterTitle"] as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { book.latestChapterTitle = it }
        JsSourceMarshaller.mergeBookInfo(
            book,
            GSON.toJson(fields.filterKeys { it in setOf("type", "downloadUrls", "variable") }),
            bookSource,
            canReName = false,
        )
        if (bookSource.bookSourceType == io.legado.app.constant.BookSourceType.file) {
            book.addType(bookSource.getBookType())
        }
        if (!book.isWebFile && book.tocUrl.isBlank()) book.tocUrl = book.bookUrl
        if (book.isWebFile && book.downloadUrls.isNullOrEmpty()) {
            throw NoStackTraceException("下载链接为空")
        }
        return book
    }

    /** 目录 */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO,
        isFromBookInfo: Boolean = false,
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs, isFromBookInfo).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(
        bookSource: BookSource,
        book: Book,
        isFromBookInfo: Boolean = false,
    ): Result<Unit> {
        return kotlin
            .runCatching {
                val script = bookSource.ruleToc?.preUpdateJs
                if (!script.isNullOrBlank()) {
                    val before = scriptSnapshot(book)
                    val sourceInfo = DartSourceEngine.jsonObject(bookSource)
                    val result =
                        DartSourceEngine.evaluate(
                            bookSource,
                            """
                    (async () => {
                        await (async function() {
                        $script
                        }).call(globalThis);
                        return {book: globalThis.book, sourceInfo: globalThis.sourceInfo};
                    })()
                    """
                                .trimIndent(),
                            before +
                                mapOf(
                                    "book" to before,
                                    "sourceInfo" to sourceInfo,
                                    "fromBookInfo" to isFromBookInfo,
                                    "baseUrl" to book.bookUrl,
                                ),
                        )
                    val returned =
                        result as? Map<*, *>
                            ?: throw UnsupportedOperationException(
                                "preUpdateJs requires migration: invalid result"
                            )
                    val returnedSource =
                        returned["sourceInfo"] as? Map<*, *>
                            ?: throw UnsupportedOperationException(
                                "preUpdateJs requires migration: sourceInfo must remain read-only"
                            )
                    if (normalizeHookJson(returnedSource) != normalizeHookJson(sourceInfo)) {
                        throw UnsupportedOperationException(
                            "preUpdateJs requires migration: sourceInfo must remain read-only"
                        )
                    }
                    applyDartPreUpdatePatch(book, before, returned["book"])
                }
                Unit
            }
            .onFailure { currentCoroutineContext().ensureActive() }
    }

    private fun normalizeHookJson(value: Any?): Any? =
        when (value) {
            // Gson and the V8 transport can represent the same JSON number with different JVM
            // types.
            // Decimal comparison also avoids rounding distinct large integer values to the same
            // Double.
            is Number -> BigDecimal(value.toString()).stripTrailingZeros()
            is Map<*, *> ->
                value.entries.associate { (key, item) ->
                    require(key is String) { "preUpdateJs requires migration: invalid JSON key" }
                    key to normalizeHookJson(item)
                }
            is List<*> -> value.map { normalizeHookJson(it) }
            else -> value
        }

    /** Validates the whole JSON mutation before changing any Android entity field. */
    @Suppress("UNCHECKED_CAST")
    internal fun applyDartPreUpdatePatch(book: Book, before: Map<String, Any?>, value: Any?) {
        val returned =
            value as? Map<*, *>
                ?: throw UnsupportedOperationException(
                    "preUpdateJs requires migration: book must remain an object"
                )
        val baseline = normalizeHookJson(before) as Map<String, Any?>
        val after = normalizeHookJson(returned) as Map<String, Any?>
        val changed =
            (baseline.keys + after.keys).filter {
                baseline[it] != after[it] || baseline.containsKey(it) != after.containsKey(it)
            }
        val requiredStrings = setOf("bookUrl", "tocUrl", "name", "author")
        val nullableStrings = setOf("coverUrl", "intro", "kind", "wordCount", "latestChapterTitle")
        for (key in changed) {
            if (
                !after.containsKey(key) ||
                    key !in requiredStrings + nullableStrings ||
                    (after[key] !is String && !(key in nullableStrings && after[key] == null))
            ) {
                throw UnsupportedOperationException(
                    "preUpdateJs requires migration: unsupported book mutation"
                )
            }
        }
        for (key in changed) {
            val text = after[key] as? String
            when (key) {
                "bookUrl" -> book.bookUrl = requireNotNull(text)
                "tocUrl" -> book.tocUrl = requireNotNull(text)
                "name" -> book.name = requireNotNull(text)
                "author" -> book.author = requireNotNull(text)
                "coverUrl" -> book.coverUrl = text
                "intro" -> book.intro = text
                "kind" -> book.kind = text
                "wordCount" -> book.wordCount = text
                "latestChapterTitle" -> book.latestChapterTitle = text
            }
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo: Boolean = false,
    ): Result<List<BookChapter>> {
        return kotlin
            .runCatching {
                if (runPerJs) runPreUpdateJs(bookSource, book, isFromBookInfo).getOrThrow()
                val legacy = usesLegacyDartFields(bookSource)
                val chapters =
                    DartSourceEngine.execute(
                            bookSource,
                            "toc",
                            scriptSnapshot(book) +
                                mapOf("book" to scriptSnapshot(book)),
                        )
                        .mapIndexed { index, row ->
                            val normalized = row.toMutableMap()
                            for (field in listOf("isVip", "isPay", "isVolume")) {
                                val value = row[field]
                                if (legacy && value is String) {
                                    normalized[field] = value.isTrue()
                                } else if (value == "true" || value == "false") {
                                    normalized[field] = value == "true"
                                } else {
                                    require(value == null || value is Boolean) {
                                        "Dart TOC $field must be a Boolean or true/false string"
                                    }
                                }
                            }
                            if (legacy && row["updateTime"] is String) {
                                normalized["tag"] = row["updateTime"]
                            }
                            GSON.fromJson(GSON.toJson(normalized), BookChapter::class.java).apply {
                                url = (row["chapterUrl"] as? String) ?: url
                                if (isVolume && url.isBlank()) url = title + index
                                bookUrl = book.bookUrl
                                baseUrl = book.tocUrl
                                this.index = index
                            }
                        }
                if (chapters.isEmpty()) {
                    throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
                }
                currentCoroutineContext().ensureActive()
                book.removeAllBookType()
                book.addType(bookSource.getBookType())
                BookChapterList.updateBookTocInfo(book, ArrayList(chapters))
                chapters
            }
            .onFailure { currentCoroutineContext().ensureActive() }
    }

    /** 章节内容 */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore,
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
    ): String {
        val saveToken =
            if (needSave) {
                BookHelp.contentSaveToken(book, bookChapter)
            } else {
                null
            }
        if (saveToken != null && saveToken.version > 0L) {
            BookHelp.getContent(book, bookChapter, saveToken)?.let {
                return it
            }
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return ""
        }
        // A missing rule is a chapter-link fallback, not an engine execution.
        val hasExplicitDartDefinition =
            bookSource.bookSourceComment.orEmpty().lineSequence().any {
                it.trim().startsWith("@source:v1 ")
            }
        if (
            !hasExplicitDartDefinition &&
                !bookSource.isJsSource() &&
                bookSource.getContentRule().content.isNullOrEmpty()
        ) {
            return bookChapter.url
        }

        val input =
            scriptSnapshot(book) +
                mapOf(
                    "book" to scriptSnapshot(book),
                    "chapter" to scriptSnapshot(bookChapter),
                    "chapterUrl" to bookChapter.getAbsoluteURL(),
                    "chapterTitle" to bookChapter.title,
                    "nextChapterUrl" to nextChapterUrl,
                    "__legacyContentFormat" to (!book.isAudio && !book.isVideo),
                    "__legacyAdaptSpecialStyle" to AppConfig.adaptSpecialStyle,
                    "__legacyOnLineTxt" to book.isOnLineTxt,
                )
        val row = DartSourceEngine.execute(bookSource, "content", input).single()
        val content =
            row["content"] as? String
                ?: throw IllegalStateException("Dart content stage did not return content")
        if (!bookChapter.isVolume && content.isBlank()) throw ContentEmptyException("内容为空")
        // Stage fields are JSON transport values, never live Java chapter objects.
        // Validate the entire patch before touching metadata or publishing the cache.
        val variable = row["variable"]
        val variables =
            when (variable) {
                null -> null
                is String -> GSON.fromJson(variable, JsonObject::class.java)
                is Map<*, *> -> GSON.toJsonTree(variable).asJsonObject
                else -> throw IllegalStateException("Dart chapter variable must be a JSON object")
            }
        require(
            variables == null ||
                variables.entrySet().all {
                    it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString
                }
        ) {
            "Dart chapter variable values must be strings"
        }
        require(!row.containsKey("imgUrl") || row["imgUrl"] == null || row["imgUrl"] is String) {
            "Dart chapter imgUrl must be a string"
        }
        variables?.let {
            bookChapter.variableMap.clear()
            it.entrySet().forEach { entry ->
                bookChapter.variableMap[entry.key] = entry.value.asString
            }
            bookChapter.variable = GSON.toJson(bookChapter.variableMap)
        }
        if (row.containsKey("imgUrl")) bookChapter.imgUrl = row["imgUrl"] as String?
        if (saveToken != null) {
            val saved =
                BookHelp.saveContent(
                    bookSource,
                    book,
                    bookChapter,
                    content,
                    saveToken,
                    saveChapterMetadata = true,
                )
            BookHelp.getContent(book, bookChapter, saveToken)?.let {
                return it
            }
            if (!saved) throw NoStackTraceException("正文缓存已更新,请重试")
        }
        return content
    }

    private val dartBatches = ConcurrentHashMap<String, BatchContentContext>()

    /** Native storage callback for the scoped Dart batch host capability. */
    fun saveDartBatchContent(batchId: String, identifier: Any?, content: String): Boolean {
        val batch = dartBatches[batchId] ?: return false
        val chapter =
            when (identifier) {
                is Map<*, *> -> {
                    val index =
                        identifier["index"] as? Number
                            ?: throw IllegalArgumentException("Batch chapter requires an index")
                    require(index.toDouble() == index.toInt().toDouble()) {
                        "Invalid chapter index"
                    }
                    batch.chapters.firstOrNull { it.index == index.toInt() }
                        ?: throw IllegalArgumentException("Chapter does not belong to this batch")
                }
                else ->
                    batch.resolveChapter(identifier)
                        ?: throw IllegalArgumentException("Batch chapter URL must be unique")
            }
        return batch.savePreparedContent(chapter, content)
    }

    /** V8 executes the hook; each callback publishes under its original cache version. */
    suspend fun getContentBatchAwait(
        bookSource: BookSource,
        book: Book,
        chapters: List<BookChapter>,
    ): List<BookChapter> {
        val script = bookSource.getContentRule().contentBatch.orEmpty()
        if (script.isBlank() && !bookSource.isJsSource()) return chapters
        val context = currentCoroutineContext()
        val batch =
            BatchContentContext(
                bookSource,
                book,
                chapters,
                context,
                chapters.associate { it.index to BookHelp.contentSaveToken(book, it) },
            )
        val batchId = UUID.randomUUID().toString()
        dartBatches[batchId] = batch
        try {
            val wrapped =
                Regex("(?is)<js>(.*?)</js>").findAll(script).map { it.groupValues[1] }.toList()
            val body =
                if (wrapped.isNotEmpty()) wrapped.joinToString("\n")
                else script.removePrefix("@js:")
            val replace = bookSource.getContentRule().replaceRegex.orEmpty()
            val replacement =
                if (replace.startsWith("@js:", ignoreCase = true))
                    "eval(" + GSON.toJson(replace.substring(4)) + ")"
                else "java.getString(" + GSON.toJson(replace) + ", result)"
            val code =
                """
                (async () => {
                    const previousJava = globalThis.java;
                    const save = (identifier, content) => {
                        const chapter = typeof identifier === 'object' && identifier !== null ? identifier :
                            chapters.filter(c => c.url === identifier || c.absoluteUrl === identifier).reduce((a,c) => a === null ? c : false, null);
                        if (!chapter) throw new Error('Batch chapter URL must be unique');
                        const baseUrl = chapter.absoluteUrl;
                        let result = String(content);
                        ${if (replace.isBlank()) "" else "result = result.split('\\n').map(s => s.trim()).join('\\n'); result = String($replacement);"}
                        ${if (replace.isNotBlank() && book.isOnLineTxt) "result = result.split('\\n').map(s => '　　' + s).join('\\n');" else ""}
                        return __sourceHostSync('batch.cacheContent', [${GSON.toJson(batchId)}, identifier, result]);
                    };
                    globalThis.java = new Proxy(previousJava || {}, {get(target,key) {
                        return key === 'cacheContent' ? save : Reflect.get(target,key);
                    }});
                    try {
                        ${if (bookSource.isJsSource()) bookSource.mainJs + "\nreturn await getContentBatch(chapters, book);" else body}
                    } finally { globalThis.java = previousJava; }
                })()
            """
                    .trimIndent()
            DartSourceEngine.evaluate(
                bookSource,
                code,
                mapOf(
                    "book" to scriptSnapshot(book),
                    "chapters" to
                        chapters.map {
                            scriptSnapshot(it) + ("absoluteUrl" to it.getAbsoluteURL())
                        },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            context.ensureActive()
            Debug.log(bookSource.bookSourceUrl, "批量正文: ${error.localizedMessage}")
        } finally {
            batch.close()
            dartBatches.remove(batchId, batch)
        }
        return batch.missingChapters()
    }

    /** 精准搜索 */
    fun preciseSearch(
        scope: CoroutineScope,
        bookSourceParts: List<BookSourcePart>,
        name: String,
        author: String,
        context: CoroutineContext = Dispatchers.IO,
        semaphore: Semaphore? = null,
    ): Coroutine<Pair<Book, BookSource>> {
        return Coroutine.async(scope, context, semaphore = semaphore) {
            for (s in bookSourceParts) {
                val source = s.getBookSource() ?: continue
                val book = preciseSearchAwait(source, name, author).getOrNull()
                if (book != null) {
                    return@async Pair(book, source)
                }
            }
            throw NoStackTraceException("没有搜索到<$name>$author")
        }
    }

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin
            .runCatching {
                currentCoroutineContext().ensureActive()
                searchBookAwait(
                        bookSource,
                        name,
                        filter = { fName, fAuthor, _ -> fName == name && fAuthor == author },
                        shouldBreak = { it > 0 },
                    )
                    .firstOrNull()
                    ?.let { searchBook ->
                        currentCoroutineContext().ensureActive()
                        return@runCatching searchBook.toBook()
                    }
                throw NoStackTraceException("未搜索到 $name($author) 书籍")
            }
            .onFailure {
                currentCoroutineContext().ensureActive()
            }
    }
}
