package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Native child owners and their potentially large results never enter the saved-state Bundle. */
enum class BookDetailChildKind { Toc,Reader,InfoEditor,SourceEditor,Folder,Cover,Group,Variable,Source }
data class BookDetailChildOwner(val token:String,val kind:BookDetailChildKind,val bookUrl:String,val sourceUrl:String?=null)
data class BookDetailChildResult(val owner:BookDetailChildOwner,val canceled:Boolean=false,val resultCode:Int=0,
    val value:String?=null,val number:Long=0,val position:BookDetailPosition?=null,val chapterChanged:Boolean=false,
    val highlightTitleLength:Int?=null,val highlightAnchor:String?=null,val source:BookDetailSource?=null,
    val network:BookDetailNetworkResult?=null)
data class BookDetailChildren(val owners:List<BookDetailChildOwner> = emptyList(),val pending:List<BookDetailChildResult> = emptyList(),
    val completed:List<String> = emptyList()) {
    fun owner(value:BookDetailChildOwner):BookDetailChildren {
        if(value.token in completed || owners.any{it==value})return this
        check(pending.none{it.owner.kind==value.kind}){"The previous child result must be applied first"}
        // Replacing a launcher invalidates an earlier unreturned owner.
        return copy(owners=owners.filter{it.kind!=value.kind}+value)
    }
    fun result(value:BookDetailChildResult):BookDetailChildren {
        if(value.owner.token in completed || pending.any{it.owner.token==value.owner.token})return this
        if(owners.none{it==value.owner})return this
        return copy(pending=pending+value)
    }
    fun complete(token:String):BookDetailChildren {
        if(pending.none{it.owner.token==token})return this
        return copy(owners=owners.filter{it.token!=token},pending=pending.filter{it.owner.token!=token},completed=(completed+token).takeLast(64))
    }
}
interface BookDetailChildRepository {
    suspend fun read(ticket:String):BookDetailChildren
    suspend fun owner(ticket:String,value:BookDetailChildOwner)
    suspend fun result(ticket:String,value:BookDetailChildResult)
    suspend fun complete(ticket:String,token:String)
    suspend fun release(ticket:String)
}
/** A separate, fenced ledger permits an Activity result to arrive before the detail VM has finished loading. */
class FileBookDetailChildRepository(context:Context,private val directory:File=File(context.applicationContext.filesDir,"book-detail-children"),
    private val beforeWrite:(BookDetailChildren)->Unit={}):BookDetailChildRepository {
    private fun body(ticket:String):AtomicFile {require(runCatching{UUID.fromString(ticket)}.isSuccess);return AtomicFile(File(directory,"$ticket.json"))}
    private fun marker(ticket:String)=AtomicFile(File(directory,"$ticket.closed"))
    private fun closed(ticket:String)=marker(ticket).baseFile.let{it.exists() || File(it.path+".bak").exists()}
    private fun gate(ticket:String)=gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE)%gates.size]
    private fun readBody(ticket:String):BookDetailChildren {
        if(closed(ticket))return BookDetailChildren()
        val file=body(ticket)
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return BookDetailChildren()
        return file.openRead().bufferedReader().use{GSON.fromJsonObject<BookDetailChildren>(it.readText()).getOrThrow()}
    }
    private fun writeBody(ticket:String,value:BookDetailChildren) {
        check(!closed(ticket)){"Book detail child session is closed"};beforeWrite(value);directory.mkdirs()
        val file=body(ticket);val stream=file.startWrite()
        try{stream.write(GSON.toJson(value).toByteArray());file.finishWrite(stream)}catch(error:Throwable){file.failWrite(stream);throw error}
    }
    override suspend fun read(ticket:String)=withContext(Dispatchers.IO){gate(ticket).withLock{readBody(ticket)}}
    private suspend fun update(ticket:String,transform:(BookDetailChildren)->BookDetailChildren)=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            check(!closed(ticket)){"Book detail child session is closed"}
            val before=readBody(ticket);val after=transform(before)
            if(after!=before)writeBody(ticket,after)
        }
    }
    override suspend fun owner(ticket:String,value:BookDetailChildOwner)=update(ticket){it.owner(value)}
    override suspend fun result(ticket:String,value:BookDetailChildResult)=update(ticket){it.result(value)}
    override suspend fun complete(ticket:String,token:String)=update(ticket){it.complete(token)}
    override suspend fun release(ticket:String)=withContext(Dispatchers.IO+NonCancellable) {
        gate(ticket).withLock {
            directory.mkdirs();val fence=marker(ticket)
            if(!closed(ticket)) {
                val stream=fence.startWrite()
                try{stream.write(1);fence.finishWrite(stream)}catch(error:Throwable){fence.failWrite(stream);throw error}
            }
            val file=body(ticket);file.delete()
            check(listOf("",".bak",".new").none{File(file.baseFile.path+it).exists()}){"Book detail child cleanup failed"}
        }
    }
    companion object{private val gates=Array(64){Mutex()}}
}
