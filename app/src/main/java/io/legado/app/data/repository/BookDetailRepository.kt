package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.*
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Intent identity is privately persisted by the session layer, never as large SavedState arguments. */
@Keep
data class BookDetailIdentity(val name:String="",val author:String="",val bookUrl:String="")

/** Full JSON preserves opaque rule variables and reader configuration without exposing mutable entities to UI state. */
@Keep
data class BookDetailBook(val json:String,val bookUrl:String,val name:String,val author:String,val realAuthor:String,
    val origin:String,val originName:String,val cover:CoverRequest,val intro:String?,val kinds:List<String>,
    val latestChapterTitle:String?,val chapterTitle:String?,val chapterIndex:Int,val chapterPos:Int,
    val totalChapterNum:Int,val group:Long,val isLocal:Boolean,val isLocalTxt:Boolean,val isWebFile:Boolean,
    val isAudio:Boolean,val isVideo:Boolean,val isImage:Boolean,val canUpdate:Boolean,val splitLongChapter:Boolean,
    val readPercent:Int?) {
    companion object {
        fun from(book:Book)=BookDetailBook(GSON.toJson(book),book.bookUrl,book.name,book.author,book.getRealAuthor(),
            book.origin,book.originName,CoverRequest.from(book),book.getDisplayIntro(),book.getKindList().toList(),
            book.latestChapterTitle,book.durChapterTitle,book.durChapterIndex,book.durChapterPos,book.totalChapterNum,
            book.group,book.isLocal,book.isLocalTxt,book.isWebFile,book.isAudio,book.isVideo,book.isImage,
            book.canUpdate,book.getSplitLongChapter(),if(book.totalChapterNum<=1)null else book.readProgress()?.let{(it*100).roundToInt()})
    }
}
@Keep
data class BookDetailChapter(val json:String,val index:Int,val url:String,val title:String,val isVolume:Boolean) {
    companion object {fun from(chapter:BookChapter)=BookDetailChapter(GSON.toJson(chapter),chapter.index,chapter.url,chapter.getDisplayTitle(chineseConvert=false),chapter.isVolume)}
}
@Keep
data class BookDetailSource(val json:String,val url:String,val name:String,val hasLogin:Boolean,val customButton:Boolean) {
    companion object {fun from(source:BookSource)=BookDetailSource(GSON.toJson(source),source.bookSourceUrl,source.bookSourceName,source.hasLogin(),source.customButton)}
}
@Keep
data class BookDetailData(val book:BookDetailBook,val source:BookDetailSource?,val chapters:List<BookDetailChapter>,
    val groupNames:List<String>,val kinds:List<String>,val inBookshelf:Boolean)

/** Fresh, detached native payloads are materialized only at data/host boundaries. */
fun BookDetailBook.materializeBook():Book=GSON.fromJsonObject<Book>(json).getOrThrow()
fun BookDetailChapter.materializeChapter():BookChapter=GSON.fromJsonObject<BookChapter>(json).getOrThrow()
fun BookDetailSource.materializeSource():BookSource=GSON.fromJsonObject<BookSource>(json).getOrThrow()

interface BookDetailRepository {
    suspend fun resolve(identity:BookDetailIdentity):BookDetailData?
    suspend fun reload(bookUrl:String):BookDetailData?
    suspend fun describe(book:BookDetailBook,inBookshelf:Boolean):BookDetailData
}

class RoomBookDetailRepository(private val database:AppDatabase=appDb,
    private val localSize:(Book)->Long={book->FileDoc.fromUri(book.getLocalUri(),false).size}):BookDetailRepository {
    override suspend fun resolve(identity:BookDetailIdentity):BookDetailData?=withContext(Dispatchers.IO) {
        // Preserve the existing name/author-first lookup, then URL and source-backed search fallback.
        val owned=database.bookDao.getBook(identity.name,identity.author)
            ?: identity.bookUrl.takeIf{it.isNotBlank()}?.let{database.bookDao.getBook(it)}
        if(owned!=null)return@withContext describeEntity(owned,!owned.isNotShelf)
        val search=identity.bookUrl.takeIf{it.isNotBlank()}?.let{database.searchBookDao.getSearchBook(it)}
            ?: database.searchBookDao.getFirstByNameAuthor(identity.name,identity.author)
        search?.toBook()?.let{describeEntity(it,false)}
    }
    override suspend fun reload(bookUrl:String):BookDetailData?=withContext(Dispatchers.IO) {
        database.bookDao.getBook(bookUrl)?.let{describeEntity(it,!it.isNotShelf)}
    }
    override suspend fun describe(book:BookDetailBook,inBookshelf:Boolean):BookDetailData=withContext(Dispatchers.IO) {
        describeEntity(book.materializeBook(),inBookshelf)
    }
    private suspend fun describeEntity(book:Book,inBookshelf:Boolean):BookDetailData {
        val snapshot=BookDetailBook.from(book)
        val source=if(book.isLocal)null else database.bookSourceDao.getBookSource(book.origin)?.let(BookDetailSource::from)
        val chapters=database.bookChapterDao.getChapterList(book.bookUrl).map(BookDetailChapter::from)
        val kinds=snapshot.kinds.toMutableList()
        if(book.isLocal) {
            val size=try{localSize(book)}catch(error:Exception){currentCoroutineContext().ensureActive();0L}
            if(size>0)kinds+=ConvertUtils.formatFileSize(size)
        }
        currentCoroutineContext().ensureActive()
        return BookDetailData(snapshot,source,chapters,database.bookGroupDao.getGroupNames(book.group).toList(),kinds.toList(),inBookshelf)
    }
}
