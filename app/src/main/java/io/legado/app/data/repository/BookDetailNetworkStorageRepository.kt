package io.legado.app.data.repository

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.book.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Keep
data class BookDetailNetworkWritePlan(val beforeJson:String,val targetJson:String,val beforeChaptersJson:List<String>,
    val chaptersJson:List<String>,val moveCache:Boolean,val sourceChanged:Boolean)
data class BookDetailNetworkStorageResult(val book:BookDetailBook,val inBookshelf:Boolean)
interface BookDetailNetworkStorageRepository {
    suspend fun commit(data:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,
        journal:(BookDetailNetworkWritePlan)->Unit):BookDetailNetworkStorageResult
    suspend fun recover(plan:BookDetailNetworkWritePlan):BookDetailNetworkStorageResult
}

/** Data-layer adaptation of the existing BookInfoViewModel.loadChapter/changeTo persistence contract. */
class RoomBookDetailNetworkStorageRepository(private val database:AppDatabase=appDb,
    private val moveCache:(Book,Book)->Unit=BookHelp::updateCacheFolder,
    private val snapshotReading:(Book)->Unit={it.saveReadRecordSnapshot()}):BookDetailNetworkStorageRepository {
    override suspend fun commit(data:BookDetailData,result:BookDetailNetworkResult,sourceChanged:Boolean,
        journal:(BookDetailNetworkWritePlan)->Unit):BookDetailNetworkStorageResult=withContext(Dispatchers.IO+NonCancellable) {
        val original=data.book.materializeBook();val parsed=result.book.materializeBook()
        var plan:BookDetailNetworkWritePlan?=null
        val outcome=database.runInTransaction<BookDetailNetworkStorageResult> {
            val current=if(data.inBookshelf)database.bookDao.getBook(original.bookUrl) ?: throw BookDetailMissing()
            else if(!sourceChanged)database.bookDao.getBook(parsed.name,parsed.author)?.takeIf{!it.isNotShelf && it.origin==parsed.origin}
            else null
            if(current==null) {
                val book=if(sourceChanged)original.migrateTo(parsed,result.chapters.map{it.materializeChapter()})else parsed
                return@runInTransaction BookDetailNetworkStorageResult(BookDetailBook.from(book),false)
            }
            val beforeJson=GSON.toJson(current.copy())
            val beforeChapters=database.bookChapterDao.getChapterList(current.bookUrl).map{GSON.toJson(it)}
            val target=if(sourceChanged)current.migrateTo(parsed,result.chapters.map{it.materializeChapter()})
                else if(data.inBookshelf)mergeBookDetailNetworkMetadata(original,parsed,current)
                else current.updateTo(parsed)
            if(target.bookUrl!=current.bookUrl && database.bookDao.getBook(target.bookUrl)!=null)throw BookDetailConflict()
            val chapters=if(target.isWebFile && !sourceChanged)database.bookChapterDao.getChapterList(current.bookUrl)
                .map{it.copy(bookUrl=target.bookUrl)}
                else result.chapters.map{it.materializeChapter().copy(bookUrl=target.bookUrl)}
            if(sourceChanged || !target.isWebFile)target.removeType(BookType.updateError)
            val write=BookDetailNetworkWritePlan(beforeJson,GSON.toJson(target.copy()),beforeChapters,
                chapters.map{GSON.toJson(it)},!sourceChanged && current.bookUrl!=target.bookUrl,sourceChanged)
            journal(write);plan=write
            if(sourceChanged)snapshotReading(current)
            persist(current,target,chapters,sourceChanged)
            BookDetailNetworkStorageResult(BookDetailBook.from(target),true)
        }
        plan?.takeIf{it.moveCache}?.let{moveCache(GSON.fromJsonObject<Book>(it.beforeJson).getOrThrow(),GSON.fromJsonObject<Book>(it.targetJson).getOrThrow())}
        outcome
    }
    private fun persist(before:Book,target:Book,chapters:List<BookChapter>,sourceChanged:Boolean) {
        // The old downloadable-file path updates its existing row without deleting its chapters.
        if(target.isWebFile && !sourceChanged && before.bookUrl==target.bookUrl)database.bookDao.updatePreservingCustomCoverUrl(target)
        else database.bookDao.replace(before,target)
        if(sourceChanged || !target.isWebFile || before.bookUrl!=target.bookUrl) {
            database.bookChapterDao.delByBook(before.bookUrl)
            database.bookChapterDao.insert(*chapters.toTypedArray())
        }
    }

    override suspend fun recover(plan:BookDetailNetworkWritePlan):BookDetailNetworkStorageResult=withContext(Dispatchers.IO+NonCancellable) {
        val before=GSON.fromJsonObject<Book>(plan.beforeJson).getOrThrow();val target=GSON.fromJsonObject<Book>(plan.targetJson).getOrThrow()
        database.runInTransaction {
            val current=database.bookDao.getBook(target.bookUrl)
            val baseline=database.bookDao.getBook(before.bookUrl)
            val complete=current?.let{GSON.toJson(it.copy())==plan.targetJson}==true &&
                database.bookChapterDao.getChapterList(target.bookUrl).map{GSON.toJson(it)}==plan.chaptersJson
            if(!complete) {
                if(baseline==null)throw BookDetailMissing()
                if(GSON.toJson(baseline.copy())!=plan.beforeJson || (current!=null && current.bookUrl!=before.bookUrl) || database.bookChapterDao.getChapterList(before.bookUrl).map{GSON.toJson(it)}!=plan.beforeChaptersJson)throw BookDetailConflict()
                if(plan.sourceChanged)snapshotReading(baseline)
                persist(baseline,target,plan.chaptersJson.map{GSON.fromJsonObject<BookChapter>(it).getOrThrow()},plan.sourceChanged)
            }
        }
        if(plan.moveCache)moveCache(before,target)
        BookDetailNetworkStorageResult(BookDetailBook.from(target),true)
    }
}


/** Apply script deltas only while the corresponding latest stored value still matches the request. */
internal fun mergeBookDetailNetworkMetadata(request:Book,parsed:Book,latest:Book):Book {
    val before=GSON.toJsonTree(request.copy()).asJsonObject
    val fetched=GSON.toJsonTree(parsed.copy()).asJsonObject
    val current=GSON.toJsonTree(latest.copy()).asJsonObject
    val merged=mergeBookDetailJson(before,fetched,current)
    // The existing replace contract always retains current custom covers and reading configuration.
    merged.add("customCoverUrl",current.get("customCoverUrl"))
    merged.add("persistedCoverUrl",current.get("persistedCoverUrl"))
    if(!parsed.isWebFile)merged.add("readConfig",current.get("readConfig"))
    val variable=mergeBookDetailJsonString(request.variable,parsed.variable,latest.variable)
    val result=GSON.fromJson(merged,Book::class.java)
    result.variable=variable
    var mergedType=0
    for(bit in 0 until Int.SIZE_BITS) {
        val mask=1 shl bit
        val requested=request.type and mask
        val fetchedBit=parsed.type and mask
        val latestBit=latest.type and mask
        mergedType=mergedType or if(latestBit==requested)fetchedBit else latestBit
    }
    result.type=mergedType
    result.infoHtml=parsed.infoHtml;result.tocHtml=parsed.tocHtml;result.downloadUrls=parsed.downloadUrls
    return result
}
private fun mergeBookDetailJson(before:JsonObject,fetched:JsonObject,current:JsonObject):JsonObject {
    val result=JsonObject()
    (before.keySet()+fetched.keySet()+current.keySet()).forEach{key->
        val a=before.get(key);val b=fetched.get(key);val c=current.get(key)
        val value:JsonElement?=if(a?.isJsonObject==true && b?.isJsonObject==true && c?.isJsonObject==true)
            mergeBookDetailJson(a.asJsonObject,b.asJsonObject,c.asJsonObject)
        else if(c==a)b else c
        if(value!=null)result.add(key,value)
    }
    return result
}
private fun mergeBookDetailJsonString(before:String?,fetched:String?,current:String?):String? {
    if(before==fetched)return current
    fun objectOrNull(value:String?):JsonObject?=if(value==null)JsonObject() else runCatching{GSON.fromJson(value,JsonObject::class.java)}.getOrNull()
    val a=objectOrNull(before);val b=objectOrNull(fetched);val c=objectOrNull(current)
    return if(a!=null && b!=null && c!=null)GSON.toJson(mergeBookDetailJson(a,b,c))
        else if(before==current)fetched else current
}
