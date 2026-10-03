package io.legado.app.data.repository

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.UrlUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BookDetailNoSource:IllegalStateException("Book source no longer exists")
data class BookDetailWebFile(val url:String,val name:String) {
    val suffix:String get()=UrlUtil.getSuffix(name)
    val supported:Boolean get()=AppPattern.bookFileRegex.matches(name)
    val archive:Boolean get()=AppPattern.archiveFileRegex.matches(name)
}
data class BookDetailNetworkResult(val book:BookDetailBook,val chapters:List<BookDetailChapter>,val webFiles:List<BookDetailWebFile>)

/** Engine bridge owns fresh mutable inputs; immutable requests/results never retain UI owners. */
interface BookDetailNetworkEngine {
    suspend fun info(book:Book,source:BookSource?,canRename:Boolean):Book
    suspend fun toc(book:Book,source:BookSource?,runPreUpdate:Boolean,fromBookInfo:Boolean):List<BookChapter>
    suspend fun files(book:Book,source:BookSource?):List<BookDetailWebFile>
    suspend fun cover(book:Book):String?
}
object DefaultBookDetailNetworkEngine:BookDetailNetworkEngine {
    override suspend fun info(book:Book,source:BookSource?,canRename:Boolean):Book {
        if(book.isLocal){LocalBook.upBookInfo(book);return book}
        return WebBook.getBookInfoAwait(source ?: throw BookDetailNoSource(),book,canRename)
    }
    override suspend fun toc(book:Book,source:BookSource?,runPreUpdate:Boolean,fromBookInfo:Boolean):List<BookChapter> {
        if(book.isLocal)return LocalBook.getChapterList(book)
        return WebBook.getChapterListAwait(source ?: throw BookDetailNoSource(),book,runPreUpdate,fromBookInfo).getOrThrow()
    }
    override suspend fun files(book:Book,source:BookSource?):List<BookDetailWebFile> {
        val fallback=if(book.author.isBlank())book.name else "${book.name} 作者：${book.author}"
        return checkNotNull(book.downloadUrls){"Missing web file download URLs"}.map{raw->
            val parsed=AnalyzeUrl(raw,source=source,coroutineContext=currentCoroutineContext())
            val name=UrlUtil.getFileName(parsed)
            BookDetailWebFile(raw,normalizeBookDetailWebFileName(name ?: fallback,parsed.type,replaceExistingSuffix=name!=null))
        }
    }
    override suspend fun cover(book:Book):String?=BookCover.searchCover(book)
}

interface BookDetailNetworkRepository {
    suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult
    suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean=false):BookDetailNetworkResult
    suspend fun files(book:BookDetailBook,source:BookDetailSource?):List<BookDetailWebFile>
    suspend fun cover(book:BookDetailBook):BookDetailBook?
}
class EngineBookDetailNetworkRepository(private val engine:BookDetailNetworkEngine=DefaultBookDetailNetworkEngine,private val io:CoroutineDispatcher=Dispatchers.IO):BookDetailNetworkRepository {
    override suspend fun info(book:BookDetailBook,source:BookDetailSource?,canRename:Boolean,runPreUpdate:Boolean):BookDetailNetworkResult=withContext(io) {
        val native=book.materializeBook();val nativeSource=source?.materializeSource()
        val updated=engine.info(native,nativeSource,canRename)
        currentCoroutineContext().ensureActive()
        finish(updated,nativeSource,if(updated.isLocal)false else runPreUpdate,true)
    }
    override suspend fun toc(book:BookDetailBook,source:BookDetailSource?,runPreUpdate:Boolean,fromBookInfo:Boolean):BookDetailNetworkResult=withContext(io) {
        finish(book.materializeBook(),source?.materializeSource(),runPreUpdate,fromBookInfo)
    }
    private suspend fun finish(book:Book,source:BookSource?,runPreUpdate:Boolean,fromBookInfo:Boolean):BookDetailNetworkResult {
        if(book.isWebFile) {
            val files=engine.files(book,source).toList();currentCoroutineContext().ensureActive()
            book.latestChapterTitle="已下载"
            return BookDetailNetworkResult(BookDetailBook.from(book),emptyList(),files)
        }
        val chapters=engine.toc(book,source,runPreUpdate,fromBookInfo).map(BookDetailChapter::from)
        currentCoroutineContext().ensureActive()
        return BookDetailNetworkResult(BookDetailBook.from(book),chapters,emptyList())
    }
    override suspend fun files(book:BookDetailBook,source:BookDetailSource?):List<BookDetailWebFile> = withContext(io) {
        val files=engine.files(book.materializeBook(),source?.materializeSource()).toList();currentCoroutineContext().ensureActive();files
    }
    override suspend fun cover(book:BookDetailBook):BookDetailBook?=withContext(io) {
        val native=book.materializeBook()
        if(!native.getDisplayCover().isNullOrBlank())return@withContext null
        val path=engine.cover(native)?.takeIf{it.isNotBlank()} ?: return@withContext null
        currentCoroutineContext().ensureActive();native.customCoverUrl=path;BookDetailBook.from(native)
    }
}

fun normalizeBookDetailWebFileName(fileName:String,rawSuffix:String?,replaceExistingSuffix:Boolean=true):String {
    val suffix=rawSuffix?.trim()?.trimStart('.')?.takeIf{it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))} ?: return fileName
    val base=fileName.trimEnd('.');if(base.isEmpty())return fileName
    val dot=base.lastIndexOf('.');if(dot<=0)return "$base.$suffix"
    if(base.substring(dot+1).equals(suffix,true))return base
    return if(replaceExistingSuffix)"${base.substring(0,dot)}.$suffix" else "$base.$suffix"
}
