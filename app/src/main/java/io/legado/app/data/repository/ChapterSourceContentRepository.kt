package io.legado.app.data.repository

import io.legado.app.model.book.ChapterSourceCacheDigest
import java.util.UUID
import kotlinx.coroutines.*

internal data class ChapterSourceChapter(
    val key: String,
    val index: Int,
    val title: String,
    val volume: Boolean,
    val tag: String?,
    val json: String,
)

internal data class ChapterSourceToc(
    val key: String,
    val bookJson: String,
    val sourceJson: String,
    val chapters: List<ChapterSourceChapter>,
    val currentIndex: Int,
)

internal enum class ChapterSourceReceiptKind {
    Content,
    Cache,
    Change,
}

internal data class ChapterSourceReceipt(
    val key: String,
    val kind: ChapterSourceReceiptKind,
    val body: String? = null,
    val chapterIndex: Int? = null,
    val targetPosition: Int? = null,
    val bookJson: String? = null,
    val sourceJson: String? = null,
    val chapters: List<ChapterSourceChapter> = emptyList(),
    val deleteAfterId: String? = null,
    val committed: Boolean = false,
    val consumed: Boolean = false,
    val previousBodyHash: String? = null,
)

internal data class ChapterSourceAutomation(
    val chapters: List<ChapterSourceChapter>,
    val position: Int = 0,
    val target: ChapterSourceToc,
    val stage: String = "Ready",
    val reason: String? = null,
    val positions: List<Int> = emptyList(),
    val stopAfterCurrent: Boolean = false,
)

internal data class ChapterSourceSession(
    val request: ChapterSourceSearchRequest,
    val chapterIndex: Int,
    val chapterTitle: String,
    val batch: Boolean,
    val originalChapters: List<ChapterSourceChapter> = emptyList(),
    val toc: ChapterSourceToc? = null,
    val tocVisible: Boolean = false,
    val selected: Set<Int> = emptySet(),
    val rows: List<ChapterSourceSearchRow> = emptyList(),
    val pendingReceipt: String? = null,
    val automation: ChapterSourceAutomation? = null,
    val finished: Boolean = false,
    val revision: Long = 0,
)

internal interface ChapterSourceContentStore {
    suspend fun original(bookJson: String): List<ChapterSourceChapter>

    suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String): ChapterSourceToc

    suspend fun content(toc: ChapterSourceToc, position: Int): String

    suspend fun saveText(
        bookJson: String,
        chapter: ChapterSourceChapter,
        body: String,
        expectedPreviousHash: String,
    ): Boolean

    suspend fun cachedText(bookJson: String, chapter: ChapterSourceChapter): String?

    suspend fun read(session: String): ChapterSourceSession?

    suspend fun write(session: String, snapshot: ChapterSourceSession)

    suspend fun receipt(session: String, key: String): ChapterSourceReceipt?

    suspend fun writeReceipt(session: String, receipt: ChapterSourceReceipt)

    suspend fun receipts(session: String): List<ChapterSourceReceipt>

    suspend fun deleteSource(row: ChapterSourceSearchRow)

    suspend fun disableSource(row: ChapterSourceSearchRow)

    suspend fun order(row: ChapterSourceSearchRow, top: Boolean)

    suspend fun score(row: ChapterSourceSearchRow, score: Int)

    suspend fun groups(): List<String>
}

internal interface ChapterSourceContentRepository {
    suspend fun original(bookJson: String): List<ChapterSourceChapter>

    suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String): ChapterSourceToc

    suspend fun content(session: String, toc: ChapterSourceToc, position: Int): ChapterSourceReceipt

    suspend fun cache(
        session: String,
        toc: ChapterSourceToc,
        positions: List<Int>,
        originalBookJson: String,
        originalChapter: ChapterSourceChapter,
        onCommit: suspend () -> Unit = {},
    ): ChapterSourceReceipt

    suspend fun read(session: String): ChapterSourceSession?

    suspend fun write(session: String, snapshot: ChapterSourceSession)

    suspend fun receipt(session: String, key: String): ChapterSourceReceipt

    suspend fun recoverCache(
        session: String,
        originalBookJson: String,
        originalChapters: List<ChapterSourceChapter>,
    ): ChapterSourceReceipt?

    suspend fun consume(session: String, key: String)

    suspend fun abandonUncommittedCache(session: String)

    suspend fun change(
        session: String,
        toc: ChapterSourceToc,
        deleteAfterId: String?,
    ): ChapterSourceReceipt

    suspend fun deleteSource(row: ChapterSourceSearchRow)

    suspend fun disableSource(row: ChapterSourceSearchRow)

    suspend fun order(row: ChapterSourceSearchRow, top: Boolean)

    suspend fun score(row: ChapterSourceSearchRow, score: Int)

    suspend fun groups(): List<String>
}

internal class DefaultChapterSourceContentRepository(
    private val store: ChapterSourceContentStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ChapterSourceContentRepository {
    override suspend fun original(bookJson: String) = withContext(io) { store.original(bookJson) }

    override suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String) =
        withContext(io) { store.toc(row, index, title) }

    override suspend fun content(session: String, toc: ChapterSourceToc, position: Int) =
        withContext(io) {
            require(toc.chapters.getOrNull(position)?.volume == false)
            val body = store.content(toc, position)
            currentCoroutineContext().ensureActive()
            val receipt =
                ChapterSourceReceipt(
                    UUID.randomUUID().toString(),
                    ChapterSourceReceiptKind.Content,
                    body = body,
                    committed = true,
                )
            store.writeReceipt(session, receipt)
            receipt
        }

    override suspend fun cache(
        session: String,
        toc: ChapterSourceToc,
        positions: List<Int>,
        originalBookJson: String,
        originalChapter: ChapterSourceChapter,
        onCommit: suspend () -> Unit,
    ) =
        withContext(io) {
            val selected = positions.distinct().sorted()
            require(
                selected.isNotEmpty() &&
                    selected.all { toc.chapters.getOrNull(it)?.volume == false }
            )
            val contents = selected.map { store.content(toc, it) }
            val body = mergeChapterSourceBody(contents)
            require(body.isNotBlank()) { "正文为空" }
            currentCoroutineContext().ensureActive()
            val receipt =
                ChapterSourceReceipt(
                    UUID.randomUUID().toString(),
                    ChapterSourceReceiptKind.Cache,
                    body = body,
                    chapterIndex = originalChapter.index,
                    targetPosition = selected.last() + 1,
                    previousBodyHash =
                        ChapterSourceCacheDigest.of(
                            store.cachedText(originalBookJson, originalChapter)
                        ),
                )
            // The durable journal precedes the irreversible cache commit. Recovery compares the
            // exact body.
            withContext(NonCancellable) {
                onCommit()
                store.writeReceipt(session, receipt)
                check(
                    store.saveText(
                        originalBookJson,
                        originalChapter,
                        body,
                        requireNotNull(receipt.previousBodyHash),
                    )
                ) {
                    "缓存已被其它操作更新，请重新获取正文"
                }
                receipt.copy(committed = true).also { store.writeReceipt(session, it) }
            }
        }

    override suspend fun recoverCache(
        session: String,
        originalBookJson: String,
        originalChapters: List<ChapterSourceChapter>,
    ) =
        withContext(io) {
            store
                .receipts(session)
                .lastOrNull { it.kind == ChapterSourceReceiptKind.Cache && !it.consumed }
                ?.let { receipt ->
                    if (receipt.committed) receipt
                    else {
                        val chapter =
                            originalChapters.firstOrNull { it.index == receipt.chapterIndex }
                                ?: error("原章节不存在")
                        val body = requireNotNull(receipt.body)
                        withContext(NonCancellable) {
                            if (store.cachedText(originalBookJson, chapter) != body) {
                                val previous =
                                    receipt.previousBodyHash ?: error("缓存提交记录缺少校验，请重新获取正文")
                                check(store.saveText(originalBookJson, chapter, body, previous)) {
                                    "缓存已被其它操作更新，请重新获取正文"
                                }
                            }
                            receipt.copy(committed = true).also { store.writeReceipt(session, it) }
                        }
                    }
                }
        }

    override suspend fun read(session: String) = withContext(io) { store.read(session) }

    override suspend fun write(session: String, snapshot: ChapterSourceSession) =
        withContext(io + NonCancellable) { store.write(session, snapshot) }

    override suspend fun receipt(session: String, key: String) =
        withContext(io) { requireNotNull(store.receipt(session, key)) { "结果不存在" } }

    override suspend fun consume(session: String, key: String) =
        withContext(io + NonCancellable) {
            store.receipt(session, key)?.let {
                store.writeReceipt(session, it.copy(consumed = true))
            }
            Unit
        }

    override suspend fun abandonUncommittedCache(session: String) =
        withContext(io + NonCancellable) {
            store
                .receipts(session)
                .filter {
                    it.kind == ChapterSourceReceiptKind.Cache && !it.committed && !it.consumed
                }
                .forEach { store.writeReceipt(session, it.copy(consumed = true)) }
        }

    override suspend fun change(session: String, toc: ChapterSourceToc, deleteAfterId: String?) =
        withContext(io) {
            val receipt =
                ChapterSourceReceipt(
                    UUID.randomUUID().toString(),
                    ChapterSourceReceiptKind.Change,
                    bookJson = toc.bookJson,
                    sourceJson = toc.sourceJson,
                    chapters = toc.chapters,
                    deleteAfterId = deleteAfterId,
                    committed = true,
                )
            store.writeReceipt(session, receipt)
            receipt
        }

    override suspend fun deleteSource(row: ChapterSourceSearchRow) =
        withContext(io) { store.deleteSource(row) }

    override suspend fun disableSource(row: ChapterSourceSearchRow) =
        withContext(io) { store.disableSource(row) }

    override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) =
        withContext(io) { store.order(row, top) }

    override suspend fun score(row: ChapterSourceSearchRow, score: Int) =
        withContext(io) { store.score(row, score) }

    override suspend fun groups() = withContext(io) { store.groups() }
}

internal fun mergeChapterSourceBody(contents: List<String>): String = buildString {
    contents.forEachIndexed { index, content ->
        if (index > 0) {
            val previous = contents[index - 1]
            val last = previous.indexOfLast { !it.isWhitespace() }
            if (
                last >= 0 &&
                    previous[last] in "。！？.!?" &&
                    (last + 1 until previous.length).none { previous[it] in "\r\n" }
            )
                append('\n')
        }
        append(content)
    }
}
