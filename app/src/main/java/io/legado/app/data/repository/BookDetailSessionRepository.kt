package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

enum class BookDetailNativeKind { EditInfo,EditSource,ChangeCover,Photo,ChangeSource,Group,Login,Share,CopyBookUrl,CopyTocUrl,
    SearchName,SearchAuthor,SearchKind,SourceVariable,BookVariable,UpdateTask,Log,Toc,Reader,OpenFile,ChooseFolder,
    CustomButton,IntroImage,ReaderSync,ShelfAdded,SourceChanged,Deleted,Toast,CacheCleared,IntroAction,IntroLink }
data class BookDetailNativeEffect(val token:String,val kind:BookDetailNativeKind,val book:BookDetailBook?=null,
    val source:BookDetailSource?=null,val value:String?=null,val flag:Boolean=false,val mutation:BookDetailMutationKind?=null,
    val position:BookDetailPosition?=null,val highlightTitleLength:Int?=null,val highlightAnchor:String?=null,
    val expectedBookUrl:String?=null)
data class BookDetailOperation(val token:String,val change:BookDetailMutation,val navigation:BookDetailNativeKind?=null,
    val highlightTitleLength:Int?=null,val highlightAnchor:String?=null)
data class BookDetailPendingMutation(val operation:BookDetailOperation,val plan:BookDetailWritePlan,val inBookshelf:Boolean)
data class BookDetailPendingNetwork(val token:String,val request:BookDetailData,val result:BookDetailNetworkResult,
    val sourceChanged:Boolean,val plan:BookDetailNetworkWritePlan?=null)
enum class BookDetailPromptKind { Delete,WebFiles,UnsupportedFile,ArchiveEntries,Upload,OverwriteUpload,ExternalLink }
data class BookDetailPrompt(val kind:BookDetailPromptKind,val value:String?=null,val values:List<String> = emptyList(),
    val deleteOriginal:Boolean=false,val deleteRemote:Boolean=false,val uploadImported:Boolean=false,val readAfter:Boolean=false)
data class BookDetailSession(val identity:BookDetailIdentity,val data:BookDetailData?=null,val webFiles:List<BookDetailWebFile> = emptyList(),
    val effects:List<BookDetailNativeEffect> = emptyList(),val pendingMutation:BookDetailPendingMutation?=null,
    val pendingNetwork:BookDetailPendingNetwork?=null,val completedOperations:List<String> = emptyList(),
    val running:Boolean=false,val prompt:BookDetailPrompt?=null,val chapterChanged:Boolean=false,val revision:Long=0,
    val pendingService:BookDetailPendingService?=null)

interface BookDetailSessionRepository {
    suspend fun read(ticket:String):BookDetailSession?
    suspend fun write(ticket:String,record:BookDetailSession)
    suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation):BookDetailSession
    suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,
        result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession
    suspend fun recover(ticket:String):BookDetailSession?
    suspend fun release(ticket:String)
}

/** Large HTML, whole books, native payloads and write receipts stay in a fenced private AtomicFile. */
class FileBookDetailSessionRepository(context:Context,private val storage:BookDetailStorageRepository,
    private val details:BookDetailRepository,private val networkStorage:BookDetailNetworkStorageRepository,private val directory:File=File(context.applicationContext.filesDir,"book-detail-sessions"),
    private val beforeWrite:(BookDetailSession)->Unit={}):BookDetailSessionRepository {
    private fun file(ticket:String):AtomicFile {require(runCatching{UUID.fromString(ticket)}.isSuccess);return AtomicFile(File(directory,"$ticket.json"))}
    private fun gate(ticket:String)=gates[(file(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE)%gates.size]
    private fun fence(ticket:String)=AtomicFile(File(directory,"$ticket.closed"))
    private fun closed(ticket:String)=fence(ticket).baseFile.let{it.exists() || File(it.path+".bak").exists()}
    private fun readBody(ticket:String):BookDetailSession? {
        if(closed(ticket))return null
        val body=file(ticket);if(!body.baseFile.exists() && !File(body.baseFile.path+".bak").exists())return null
        return body.openRead().bufferedReader().use{GSON.fromJsonObject<BookDetailSession>(it.readText()).getOrThrow()}
    }
    private fun writeBody(ticket:String,record:BookDetailSession) {
        check(!closed(ticket)){"Book detail session is closed"};beforeWrite(record);directory.mkdirs()
        val body=file(ticket);val stream=body.startWrite()
        try{stream.write(GSON.toJson(record).toByteArray());body.finishWrite(stream)}catch(error:Throwable){body.failWrite(stream);throw error}
    }
    override suspend fun read(ticket:String):BookDetailSession?=withContext(Dispatchers.IO){gate(ticket).withLock{readBody(ticket)}}
    override suspend fun write(ticket:String,record:BookDetailSession)=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock{if((readBody(ticket)?.revision ?: -1)<=record.revision)writeBody(ticket,record)}
    }
    override suspend fun mutate(ticket:String,record:BookDetailSession,operation:BookDetailOperation):BookDetailSession=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            check(!closed(ticket)){"Book detail session is closed"}
            var current=readBody(ticket)?.takeIf{it.revision>record.revision || it.pendingMutation!=null} ?: record
            if(operation.token in current.completedOperations)return@withLock current
            if(current.pendingMutation==null && current.revision>record.revision)throw BookDetailConflict()
            check(current.pendingNetwork==null){"A network commit must be recovered first"}
            val data=checkNotNull(current.data){"Book details not loaded"}
            val active=current.pendingMutation?.operation ?: operation
            val afterShelf=data.inBookshelf || active.change.kind==BookDetailMutationKind.JoinShelf ||
                (active.change.kind==BookDetailMutationKind.Group && active.change.group>0)
            val result=if(current.pendingMutation!=null) {
                BookDetailStorageResult(storage.recover(current.pendingMutation!!.plan),current.pendingMutation!!.inBookshelf)
            }else storage.mutate(data.book,data.inBookshelf,data.chapters,active.change){plan->
                current=current.copy(pendingMutation=BookDetailPendingMutation(active,plan,afterShelf),revision=current.revision+1)
                writeBody(ticket,current)
            }
            var committed=result.book
            // Canonical Room receipts omit transient HTML; retain the private parsed response when recovering.
            if(current.pendingMutation!=null) {
                val native=committed.materializeBook();val cached=data.book.materializeBook()
                native.infoHtml=cached.infoHtml;native.tocHtml=cached.tocHtml;native.downloadUrls=cached.downloadUrls
                committed=BookDetailBook.from(native)
            }
            val updated=details.describe(committed,result.inBookshelf)
            val effects=current.effects.toMutableList()
            if(result.inBookshelf && !data.inBookshelf)effects+=BookDetailNativeEffect(active.token+":shelf",BookDetailNativeKind.ShelfAdded,updated.book,updated.source)
            if(result.inBookshelf)effects+=BookDetailNativeEffect(active.token,BookDetailNativeKind.ReaderSync,updated.book,updated.source,mutation=active.change.kind,expectedBookUrl=data.book.bookUrl)
            active.navigation?.let{effects+=BookDetailNativeEffect(active.token+":navigate",it,updated.book,updated.source,
                flag=current.chapterChanged,position=active.change.position,highlightTitleLength=active.highlightTitleLength,highlightAnchor=active.highlightAnchor)}
            val completed=current.copy(data=updated,pendingMutation=null,effects=effects.toList(),
                completedOperations=rememberCompleted(current,active.token),revision=current.revision+1)
            writeBody(ticket,completed);completed
        }
    }
    override suspend fun completeNetwork(ticket:String,record:BookDetailSession,request:BookDetailData,
        result:BookDetailNetworkResult,sourceChanged:Boolean,token:String):BookDetailSession=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            check(!closed(ticket)){"Book detail session is closed"}
            var current=readBody(ticket)?.takeIf{it.revision>=record.revision || it.pendingNetwork!=null} ?: record
            if(token in current.completedOperations)return@withLock current
            if(current.pendingNetwork==null && current.revision>record.revision)throw BookDetailConflict()
            check(current.pendingMutation==null){"A book mutation must be recovered first"}
            if(current.pendingNetwork==null) {
                current=current.copy(pendingNetwork=BookDetailPendingNetwork(token,request,result,sourceChanged),revision=current.revision+1)
                // The full parsed response is durable before any Room or cache changes.
                writeBody(ticket,current)
            }
            finishNetwork(ticket,current)
        }
    }
    private suspend fun finishNetwork(ticket:String,record:BookDetailSession):BookDetailSession {
        var current=record
        val pending=checkNotNull(current.pendingNetwork)
        val result=pending.plan?.let{networkStorage.recover(it)} ?: networkStorage.commit(pending.request,pending.result,pending.sourceChanged){plan->
            current=current.copy(pendingNetwork=pending.copy(plan=plan),revision=current.revision+1)
            writeBody(ticket,current)
        }
        val native=result.book.materializeBook();val cached=pending.result.book.materializeBook()
        native.infoHtml=cached.infoHtml;native.tocHtml=cached.tocHtml;native.downloadUrls=cached.downloadUrls
        val updated=details.describe(BookDetailBook.from(native),result.inBookshelf)
        val effects=current.effects.toMutableList()
        if(result.inBookshelf && !pending.request.inBookshelf)effects+=BookDetailNativeEffect(pending.token+":shelf",BookDetailNativeKind.ShelfAdded,updated.book,updated.source)
        if(result.inBookshelf)effects+=BookDetailNativeEffect(pending.token,BookDetailNativeKind.ReaderSync,updated.book,updated.source,flag=true,expectedBookUrl=pending.request.book.bookUrl)
        if(pending.sourceChanged)effects+=BookDetailNativeEffect(pending.token+":source",BookDetailNativeKind.SourceChanged,updated.book,updated.source)
        val completed=current.copy(data=updated,webFiles=pending.result.webFiles,pendingNetwork=null,running=false,
            chapterChanged=true,effects=effects,completedOperations=rememberCompleted(current,pending.token),revision=current.revision+1)
        writeBody(ticket,completed);return completed
    }
    override suspend fun recover(ticket:String):BookDetailSession?=withContext(Dispatchers.IO+NonCancellable) {
        // Mutation recovery delegates to the same operation path, outside the non-reentrant ticket lock.
        val record=gate(ticket).withLock {
            val current=readBody(ticket) ?: return@withLock null
            if(current.pendingNetwork!=null)finishNetwork(ticket,current) else current
        } ?: return@withContext null
        record.pendingMutation?.let{mutate(ticket,record,it.operation)} ?: record
    }
    private fun rememberCompleted(record:BookDetailSession,token:String)=(record.completedOperations+token).takeLast(64)
    override suspend fun release(ticket:String)=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            directory.mkdirs();val marker=fence(ticket)
            if(!closed(ticket)){val stream=marker.startWrite();try{stream.write(1);marker.finishWrite(stream)}catch(error:Throwable){marker.failWrite(stream);throw error}}
            val body=file(ticket);body.delete();check(listOf("",".bak",".new").none{File(body.baseFile.path+it).exists()}){"Book detail cleanup failed"}
        }
    }
    companion object{private val gates=Array(64){Mutex()}}
}
