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

enum class BookMetadataAction { PickCover,ChangeCover }
data class BookMetadataNavigation(val token:String,val action:BookMetadataAction)
data class BookMetadataCompletion(val token:String,val book:BookMetadataSnapshot)
data class BookMetadataDraft(val bookUrl:String,val original:BookMetadataSnapshot?=null,
    val input:BookMetadataInput?=null,val preview:CoverRequest?=null,val pendingSave:BookMetadataSave?=null,
    val navigation:BookMetadataNavigation?=null,val pickerOwner:String?=null,val coverUri:String?=null,
    val completion:BookMetadataCompletion?=null,val finished:Boolean=false,val revision:Long=0)
interface BookMetadataEditorSessionRepository {
    suspend fun read(ticket:String):BookMetadataDraft?
    suspend fun write(ticket:String,draft:BookMetadataDraft)
    suspend fun save(ticket:String,draft:BookMetadataDraft):BookMetadataDraft
    suspend fun release(ticket:String)
}

/** Private full draft, pending Room/cache save plan and one-shot native receipts. SavedState stores only its UUID. */
class FileBookMetadataEditorSessionRepository(context:Context,private val books:BookMetadataEditorRepository,
    private val directory:File=File(context.applicationContext.filesDir,"book-metadata-editor"),
    private val beforeWrite:(BookMetadataDraft)->Unit={}) : BookMetadataEditorSessionRepository {
    private fun file(ticket:String):AtomicFile {
        require(runCatching{UUID.fromString(ticket)}.isSuccess)
        return AtomicFile(File(directory,"$ticket.json"))
    }
    private fun gate(ticket:String)=gates[(file(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE)%gates.size]
    private fun fence(ticket:String)=AtomicFile(File(directory,"$ticket.closed"))
    private fun closed(ticket:String)=fence(ticket).baseFile.let{it.exists() || File(it.path+".bak").exists()}
    private fun readBody(ticket:String):BookMetadataDraft? {
        if(closed(ticket))return null
        val body=file(ticket)
        if(!body.baseFile.exists() && !File(body.baseFile.path+".bak").exists())return null
        return body.openRead().bufferedReader().use{GSON.fromJsonObject<BookMetadataDraft>(it.readText()).getOrThrow()}
    }
    private fun writeBody(ticket:String,draft:BookMetadataDraft) {
        check(!closed(ticket)){"Book editor is closed"};beforeWrite(draft);directory.mkdirs()
        val body=file(ticket);val stream=body.startWrite()
        try{stream.write(GSON.toJson(draft).toByteArray());body.finishWrite(stream)}catch(error:Throwable){body.failWrite(stream);throw error}
    }
    override suspend fun read(ticket:String)=withContext(Dispatchers.IO){gate(ticket).withLock{readBody(ticket)}}
    override suspend fun write(ticket:String,draft:BookMetadataDraft)=withContext(Dispatchers.IO+NonCancellable){gate(ticket).withLock{
        if((readBody(ticket)?.revision ?: -1)<=draft.revision)writeBody(ticket,draft)
    }}
    override suspend fun save(ticket:String,draft:BookMetadataDraft):BookMetadataDraft=withContext(Dispatchers.IO+NonCancellable){gate(ticket).withLock {
        check(!closed(ticket)){"Book editor is closed"}
        var current=readBody(ticket)?.takeIf{it.revision>draft.revision || it.pendingSave!=null || it.completion!=null || it.finished} ?: draft
        if(current.completion!=null || current.finished)return@withLock current
        val input=current.input ?: error("Book has not loaded")
        val saved=if(current.pendingSave!=null)books.recover(current.pendingSave!!)
        else books.save(input){plan->
            current=current.copy(pendingSave=plan,revision=current.revision+1)
            writeBody(ticket,current)
        }
        val completed=current.copy(pendingSave=null,completion=BookMetadataCompletion(UUID.randomUUID().toString(),saved),revision=current.revision+1)
        writeBody(ticket,completed);completed
    }}
    override suspend fun release(ticket:String)=withContext(Dispatchers.IO+NonCancellable){gate(ticket).withLock{
        directory.mkdirs()
        if(!closed(ticket)){val marker=fence(ticket);val stream=marker.startWrite();try{stream.write(1);marker.finishWrite(stream)}catch(error:Throwable){marker.failWrite(stream);throw error}}
        val body=file(ticket);body.delete();check(listOf("", ".bak", ".new").none{File(body.baseFile.path+it).exists()})
    }}
    companion object{private val gates=Array(64){Mutex()}}
}
