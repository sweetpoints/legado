package io.legado.app.model.webBook

import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.model.ExploreInfoMapStore.exploreInfoMapList
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.jsSource.JsSourceBook
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils.wordCountFormat
import io.legado.app.utils.isTrue
import kotlin.coroutines.CoroutineContext
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
            if (DartSourceEngine.selected(bookSource)) {
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
            if (bookSource.isJsSource()) {
                return@withContext JsSourceBook.searchAwait(bookSource, key, page, filter)
            }
            val searchUrl = bookSource.searchUrl
            if (searchUrl.isNullOrBlank()) {
                throw NoStackTraceException("搜索url不能为空")
            }
            val ruleData = RuleData()
            val analyzeUrl =
                AnalyzeUrl(
                    mUrl = searchUrl,
                    key = key,
                    page = page,
                    baseUrl = bookSource.bookSourceUrl,
                    source = bookSource,
                    ruleData = ruleData,
                    coroutineContext = currentCoroutineContext(),
                )
            val checkJs = bookSource.loginCheckJs
            val res =
                kotlin
                    .runCatching {
                        analyzeUrl.getStrResponseAwait().let {
                            if (!checkJs.isNullOrBlank()) { // 检测书源是否已登录
                                analyzeUrl.evalJS(checkJs, it) as StrResponse
                            } else {
                                it
                            }
                        }
                    }
                    .getOrElse { throwable ->
                        if (!checkJs.isNullOrBlank()) {
                            val errResponse = analyzeUrl.getErrStrResponse(throwable)
                            try {
                                (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                                    if (it.code() == 500) {
                                        throw throwable
                                    }
                                }
                            } catch (_: Throwable) {
                                throw throwable
                            }
                        } else {
                            throw throwable
                        }
                    }
            checkRedirect(bookSource, res)
            BookList.analyzeBookList(
                bookSource = bookSource,
                ruleData = ruleData,
                analyzeUrl = analyzeUrl,
                baseUrl = res.url,
                body = res.body,
                isSearch = true,
                isRedirect = res.raw.priorResponse?.isRedirect == true,
                filter = filter,
                shouldBreak = shouldBreak,
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
        if (DartSourceEngine.selected(bookSource)) {
            val exploreUrl =
                if (usesLegacyDartFields(bookSource)) {
                    val expanded = url.replace("{{page}}", (page ?: 1).toString())
                    require(
                        !Regex("(?i)@(?:web)?js:|<js>|javascript:|,\\s*\\{|[<>]|\\{\\{|\\}\\}")
                            .containsMatchIn(expanded)
                    ) {
                        "Legacy explore URL requires migration: scripts, request options and complex templates are unsupported"
                    }
                    expanded
                } else url
            return ArrayList(
                DartSourceEngine.execute(
                        bookSource,
                        "explore",
                        mapOf("url" to url, "exploreUrl" to exploreUrl, "page" to (page ?: 1)),
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
        if (bookSource.isJsSource()) {
            return JsSourceBook.exploreAwait(bookSource, url, page)
        }
        val ruleData = RuleData()
        val sourceUrl = bookSource.bookSourceUrl
        val exploreInfoMap = exploreInfoMapList[sourceUrl]
        val analyzeUrl =
            AnalyzeUrl(
                mUrl = url,
                page = page,
                baseUrl = sourceUrl,
                source = bookSource,
                ruleData = ruleData,
                coroutineContext = currentCoroutineContext(),
                infoMap = exploreInfoMap,
            )
        val checkJs = bookSource.loginCheckJs
        val res =
            kotlin
                .runCatching {
                    analyzeUrl.getStrResponseAwait().let {
                        if (!checkJs.isNullOrBlank()) { // 检测书源是否已登录
                            analyzeUrl.evalJS(checkJs, it) as StrResponse
                        } else {
                            it
                        }
                    }
                }
                .getOrElse { throwable ->
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrStrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                                if (it.code() == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false,
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
        if (DartSourceEngine.selected(bookSource)) {
            val fields =
                normalizeDartBookFields(
                    bookSource,
                    DartSourceEngine.execute(bookSource, "info", DartSourceEngine.jsonObject(book))
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
            if (book.tocUrl.isBlank()) book.tocUrl = book.bookUrl
            (fields["coverUrl"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.coverUrl = it }
            (fields["intro"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.intro = it }
            (fields["kind"] as? String)?.takeIf { it.isNotEmpty() }?.let { book.kind = it }
            (fields["wordCount"] as? String)
                ?.takeIf { it.isNotEmpty() }
                ?.let { book.wordCount = it }
            (fields["latestChapterTitle"] as? String)
                ?.takeIf { it.isNotEmpty() }
                ?.let { book.latestChapterTitle = it }
            return book
        }
        if (bookSource.isJsSource()) {
            return JsSourceBook.getBookInfoAwait(bookSource, book, canReName)
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName,
            )
        } else {
            val analyzeUrl =
                AnalyzeUrl(
                    mUrl = book.bookUrl,
                    baseUrl = bookSource.bookSourceUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = currentCoroutineContext(),
                )
            val checkJs = bookSource.loginCheckJs
            val res =
                kotlin
                    .runCatching {
                        analyzeUrl.getStrResponseAwait().let {
                            if (!checkJs.isNullOrBlank()) { // 检测书源是否已登录
                                analyzeUrl.evalJS(checkJs, it) as StrResponse
                            } else {
                                it
                            }
                        }
                    }
                    .getOrElse { throwable ->
                        if (!checkJs.isNullOrBlank()) {
                            val errResponse = analyzeUrl.getErrStrResponse(throwable)
                            try {
                                (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                                    if (it.code() == 500) {
                                        throw throwable
                                    }
                                }
                            } catch (_: Throwable) {
                                throw throwable
                            }
                        } else {
                            throw throwable
                        }
                    }
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName,
            )
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
                val preUpdateJs = bookSource.ruleToc?.preUpdateJs
                if (!preUpdateJs.isNullOrBlank()) {
                    AnalyzeRule(book, bookSource, true, isFromBookInfo)
                        .setCoroutineContext(currentCoroutineContext())
                        .evalJS(preUpdateJs)
                }
            }
            .onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
            }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo: Boolean = false,
    ): Result<List<BookChapter>> {
        if (DartSourceEngine.selected(bookSource)) {
            return kotlin
                .runCatching {
                    require(!runPerJs || bookSource.ruleToc?.preUpdateJs.isNullOrBlank()) {
                        "Dart engine does not support legacy preUpdateJs; migrate the source first"
                    }
                    val legacy = usesLegacyDartFields(bookSource)
                    val chapters =
                        DartSourceEngine.execute(
                                bookSource,
                                "toc",
                                DartSourceEngine.jsonObject(book),
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
                                GSON.fromJson(GSON.toJson(normalized), BookChapter::class.java)
                                    .apply {
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
        if (bookSource.isJsSource()) {
            return JsSourceBook.getChapterListAwait(bookSource, book)
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin
            .runCatching {
                if (runPerJs) {
                    runPreUpdateJs(bookSource, book, isFromBookInfo).getOrThrow()
                }
                if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                    BookChapterList.analyzeChapterList(
                        bookSource = bookSource,
                        book = book,
                        baseUrl = book.tocUrl,
                        redirectUrl = book.tocUrl,
                        body = book.tocHtml,
                        isFromBookInfo = isFromBookInfo,
                    )
                } else {
                    val analyzeUrl =
                        AnalyzeUrl(
                            mUrl = book.tocUrl,
                            baseUrl = book.bookUrl,
                            source = bookSource,
                            ruleData = book,
                            coroutineContext = currentCoroutineContext(),
                        )
                    val checkJs = bookSource.loginCheckJs
                    val res =
                        kotlin
                            .runCatching {
                                analyzeUrl.getStrResponseAwait().let {
                                    if (!checkJs.isNullOrBlank()) { // 检测书源是否已登录
                                        analyzeUrl.evalJS(checkJs, it) as StrResponse
                                    } else {
                                        it
                                    }
                                }
                            }
                            .getOrElse { throwable ->
                                if (!checkJs.isNullOrBlank()) {
                                    val errResponse = analyzeUrl.getErrStrResponse(throwable)
                                    try {
                                        (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse)
                                            .also {
                                                if (it.code() == 500) {
                                                    throw throwable
                                                }
                                            }
                                    } catch (_: Throwable) {
                                        throw throwable
                                    }
                                } else {
                                    throw throwable
                                }
                            }
                    checkRedirect(bookSource, res)
                    BookChapterList.analyzeChapterList(
                        bookSource = bookSource,
                        book = book,
                        baseUrl = book.tocUrl,
                        redirectUrl = res.url,
                        body = res.body,
                        isFromBookInfo = isFromBookInfo,
                    )
                }
            }
            .onFailure {
                currentCoroutineContext().ensureActive()
            }
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
        if (DartSourceEngine.selected(bookSource)) {
            val input =
                DartSourceEngine.jsonObject(book) +
                    mapOf(
                        "chapterUrl" to bookChapter.getAbsoluteURL(),
                        "chapterTitle" to bookChapter.title,
                        "nextChapterUrl" to nextChapterUrl,
                    )
            val row = DartSourceEngine.execute(bookSource, "content", input).single()
            val content =
                row["content"] as? String
                    ?: throw IllegalStateException("Dart content stage did not return content")
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
        if (bookSource.isJsSource()) {
            val content =
                JsSourceBook.getContentAwait(
                    bookSource,
                    book,
                    bookChapter,
                    nextChapterUrl,
                    false,
                )
            if (saveToken != null) {
                val saved =
                    BookHelp.saveContent(
                        bookSource,
                        book,
                        bookChapter,
                        content,
                        saveToken,
                    )
                BookHelp.getContent(book, bookChapter, saveToken)?.let {
                    return it
                }
                if (!saved) throw NoStackTraceException("正文缓存已更新,请重试")
            }
            return content
        }
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        val content =
            if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
                BookContent.analyzeContent(
                    bookSource = bookSource,
                    book = book,
                    bookChapter = bookChapter,
                    baseUrl = bookChapter.getAbsoluteURL(),
                    redirectUrl = bookChapter.getAbsoluteURL(),
                    body = book.tocHtml,
                    nextChapterUrl = nextChapterUrl,
                    needSave = false,
                )
            } else {
                val analyzeUrl =
                    AnalyzeUrl(
                        mUrl = bookChapter.getAbsoluteURL(),
                        baseUrl = book.tocUrl,
                        source = bookSource,
                        ruleData = book,
                        chapter = bookChapter,
                        coroutineContext = currentCoroutineContext(),
                    )
                val checkJs = bookSource.loginCheckJs
                val res =
                    kotlin
                        .runCatching {
                            analyzeUrl
                                .getStrResponseAwait(
                                    jsStr = contentRule.webJs,
                                    sourceRegex = contentRule.sourceRegex,
                                )
                                .let {
                                    if (!checkJs.isNullOrBlank()) { // 检测书源是否已登录
                                        analyzeUrl.evalJS(checkJs, it) as StrResponse
                                    } else {
                                        it
                                    }
                                }
                        }
                        .getOrElse { throwable ->
                            if (!checkJs.isNullOrBlank()) {
                                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                                try {
                                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                                        if (it.code() == 500) {
                                            throw throwable
                                        }
                                    }
                                } catch (_: Throwable) {
                                    throw throwable
                                }
                            } else {
                                throw throwable
                            }
                        }
                checkRedirect(bookSource, res)
                BookContent.analyzeContent(
                    bookSource = bookSource,
                    book = book,
                    bookChapter = bookChapter,
                    baseUrl = bookChapter.getAbsoluteURL(),
                    redirectUrl = res.url,
                    body = res.body,
                    nextChapterUrl = nextChapterUrl,
                    needSave = false,
                )
            }
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

    /**
     * 批量章节内容。
     *
     * 常规源走 contentBatch 规则,JS源走 getContentBatch 函数, 两者都通过 java.cacheContent 回存。
     * 返回书源未回存的章节,调用方按普通单章流程兜底。
     */
    suspend fun getContentBatchAwait(
        bookSource: BookSource,
        book: Book,
        chapters: List<BookChapter>,
    ): List<BookChapter> {
        if (DartSourceEngine.selected(bookSource))
            return chapters // caller's single-chapter fallback
        if (bookSource.isJsSource()) {
            return JsSourceBook.getContentBatchAwait(bookSource, book, chapters)
        }
        return BookContent.analyzeContentBatch(
            bookSource = bookSource,
            book = book,
            chapters = chapters,
        )
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

    /** 检测重定向 */
    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(bookSource.bookSourceUrl, "┌重定向后地址")
                Debug.log(bookSource.bookSourceUrl, "└${response.url}")
            }
        }
    }
}
