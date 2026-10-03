package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.removeType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class BookDetailMutationKind { Cover,Group,CanUpdate,SplitLong,CustomVariable,Top,JoinShelf,PrepareRead,PrepareToc }
data class BookDetailPosition(val index:Int,val pos:Int,val volume:Int,val chapterInVolume:Int)
data class BookDetailMutation(val kind:BookDetailMutationKind,val text:String?=null,val flag:Boolean=false,
    val group:Long=0,val position:BookDetailPosition?=null,val onlyIfCoverMissing:Boolean=false)
data class BookDetailStorageResult(val book:BookDetailBook,val inBookshelf:Boolean)
/** Canonical Room entities omit transient HTML. The session writes this plan before the transaction mutates Room. */
data class BookDetailWritePlan(val beforeJson:String?,val targetJson:String,val chaptersJson:List<String>,val beforeChaptersJson:List<String> = emptyList())
class BookDetailMissing:IllegalStateException("Book no longer exists")
class BookDetailConflict:IllegalStateException("Book changed outside this operation; reload before retrying")

interface BookDetailStorageRepository {
    suspend fun mutate(book:BookDetailBook,inBookshelf:Boolean,chapters:List<BookDetailChapter>,change:BookDetailMutation,
        journal:(BookDetailWritePlan)->Unit):BookDetailStorageResult
    suspend fun recover(plan:BookDetailWritePlan):BookDetailBook
}

/** Only requested fields change. Reader progress, groups, cover overrides and opaque variables remain fresh. */
class RoomBookDetailStorageRepository(private val database:AppDatabase=appDb,
    private val clock:()->Long=System::currentTimeMillis):BookDetailStorageRepository {
    override suspend fun mutate(book:BookDetailBook,inBookshelf:Boolean,chapters:List<BookDetailChapter>,change:BookDetailMutation,
        journal:(BookDetailWritePlan)->Unit):BookDetailStorageResult=withContext(Dispatchers.IO+NonCancellable) {
        val preview=book.materializeBook()
        database.runInTransaction<BookDetailStorageResult> {
            val current=database.bookDao.getBook(book.bookUrl)
            if(inBookshelf && current==null)throw BookDetailMissing()
            val add=change.kind==BookDetailMutationKind.JoinShelf || (change.kind==BookDetailMutationKind.Group && !inBookshelf && change.group>0)
            val prepare=change.kind==BookDetailMutationKind.PrepareRead || change.kind==BookDetailMutationKind.PrepareToc
            val persist=inBookshelf || add || prepare || (change.kind==BookDetailMutationKind.Top && current!=null)
            val inherited=current ?: if(add || prepare)database.bookDao.getBook(preview.name,preview.author)else null
            val target=GSON.fromJsonObject<Book>(GSON.toJson((inherited ?: preview).copy())).getOrThrow()
            val skipCoverRule=change.kind==BookDetailMutationKind.Cover && change.onlyIfCoverMissing &&
                (target.origin!=preview.origin || target.name!=preview.name || target.author!=preview.author || !target.getDisplayCover().isNullOrBlank())
            when(change.kind) {
                BookDetailMutationKind.Cover->{
                    if(!skipCoverRule) {
                        target.customCoverUrl=change.text;target.persistedCoverUrl=null
                    }
                }
                BookDetailMutationKind.Group->target.group=change.group
                BookDetailMutationKind.CanUpdate->{target.canUpdate=change.flag;if(inBookshelf && !change.flag)target.removeType(BookType.updateError)}
                BookDetailMutationKind.SplitLong->target.setSplitLongChapter(change.flag)
                BookDetailMutationKind.CustomVariable->target.putCustomVariable(change.text)
                BookDetailMutationKind.Top->{target.order=database.bookDao.minOrder-1;target.durChapterTime=clock()}
                BookDetailMutationKind.JoinShelf->Unit
                BookDetailMutationKind.PrepareRead->{if(!inBookshelf)target.type=target.type or BookType.notShelf}
                BookDetailMutationKind.PrepareToc->Unit
            }
            change.position?.let{target.durChapterIndex=it.index;target.durChapterPos=it.pos;target.durVolumeIndex=it.volume;target.chapterInVolumeIndex=it.chapterInVolume}
            if(add)target.removeType(BookType.notShelf)
            if(persist) {
                if(target.order==0 && !skipCoverRule)target.order=database.bookDao.minOrder-1
                // Search promotion keeps its selected source, with the latest owner's progress and user metadata.
                if(inherited!=null && inherited.bookUrl!=preview.bookUrl && (add || prepare)) {
                    target.bookUrl=preview.bookUrl;target.origin=preview.origin;target.originName=preview.originName;target.tocUrl=preview.tocUrl
                    target.type=preview.type; if(add)target.removeType(BookType.notShelf)else if(change.kind==BookDetailMutationKind.PrepareRead)target.type=target.type or BookType.notShelf
                    target.coverUrl=preview.coverUrl;target.intro=preview.intro;target.kind=preview.kind;target.latestChapterTitle=preview.latestChapterTitle;target.totalChapterNum=preview.totalChapterNum
                }
                val existingChapters=inherited?.let{database.bookChapterDao.getChapterList(it.bookUrl)}.orEmpty()
                val nativeChapters=if(add || prepare) {
                    if(inherited?.bookUrl==target.bookUrl && existingChapters.isNotEmpty())existingChapters
                    else chapters.map{it.materializeChapter().copy(bookUrl=target.bookUrl)}
                }else emptyList()
                val plan=BookDetailWritePlan(inherited?.let{GSON.toJson(it.copy())},GSON.toJson(target),nativeChapters.map{GSON.toJson(it)},existingChapters.map{GSON.toJson(it)})
                journal(plan)
                when {
                    inherited==null->database.bookDao.insert(target)
                    inherited.bookUrl!=target.bookUrl->database.bookDao.replace(inherited,target)
                    else->database.bookDao.update(target)
                }
                if(nativeChapters.isNotEmpty())database.bookChapterDao.insert(*nativeChapters.toTypedArray())
            }
            target.infoHtml=preview.infoHtml;target.tocHtml=preview.tocHtml;target.downloadUrls=preview.downloadUrls
            BookDetailStorageResult(BookDetailBook.from(target),inBookshelf || add)
        }
    }
    override suspend fun recover(plan:BookDetailWritePlan):BookDetailBook=withContext(Dispatchers.IO+NonCancellable) {
        val before=plan.beforeJson?.let{GSON.fromJsonObject<Book>(it).getOrThrow()}
        val target=GSON.fromJsonObject<Book>(plan.targetJson).getOrThrow()
        database.runInTransaction<BookDetailBook> {
            val current=database.bookDao.getBook(target.bookUrl)
            val baseline=before?.let{database.bookDao.getBook(it.bookUrl)}
            val targetExists=current?.let{GSON.toJson(it.copy())==plan.targetJson}==true
            val currentChapters=if(current==null)emptyList()else database.bookChapterDao.getChapterList(current.bookUrl).map{GSON.toJson(it)}
            val chapterComplete=plan.chaptersJson.isEmpty() || currentChapters==plan.chaptersJson
            if(targetExists && !chapterComplete && (before?.bookUrl!=target.bookUrl || currentChapters!=plan.beforeChaptersJson))throw BookDetailConflict()
            if(!targetExists) {
                if(before==null) {if(current!=null || database.bookDao.getBook(target.name,target.author)!=null)throw BookDetailConflict();database.bookDao.insert(target)}
                else {
                    if(baseline==null)throw BookDetailMissing()
                    if(GSON.toJson(baseline.copy())!=plan.beforeJson || (current!=null && current.bookUrl!=before.bookUrl) || database.bookChapterDao.getChapterList(baseline.bookUrl).map{GSON.toJson(it)}!=plan.beforeChaptersJson)throw BookDetailConflict()
                    if(before.bookUrl==target.bookUrl)database.bookDao.update(target)else database.bookDao.replace(baseline,target)
                }
            }
            if(!chapterComplete && plan.chaptersJson.isNotEmpty())database.bookChapterDao.insert(*plan.chaptersJson.map{GSON.fromJsonObject<BookChapter>(it).getOrThrow()}.toTypedArray())
            BookDetailBook.from(target)
        }
    }
}
