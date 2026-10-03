package io.legado.app.data.repository

import kotlinx.coroutines.*

enum class BookDetailServiceKind { Refresh,ClearCache,Delete,UploadCheck,Upload,Download,ArchiveList,ArchiveImport }
data class BookDetailServiceRequest(val token:String,val kind:BookDetailServiceKind,val book:BookDetailBook,
    val source:BookDetailSource?=null,val file:BookDetailWebFile?=null,val uri:String?=null,val entry:String?=null,
    val deleteOriginal:Boolean=false,val deleteRemote:Boolean=false,val overwrite:Boolean=false,
    val readAfter:Boolean=false,val uploadImported:Boolean=false)
data class BookDetailServiceResult(val book:BookDetailBook?=null,val uri:String?=null,val entries:List<String> = emptyList(),
    val exists:Boolean=false,val warning:String?=null)
data class BookDetailPendingService(val request:BookDetailServiceRequest,val result:BookDetailServiceResult?=null)
interface BookDetailServiceSessionRepository {
    suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession
}

private class BookDetailServiceReceiptFailure(cause:Throwable):IllegalStateException("Failed to retain accepted service result",cause)

/** Engine results and native continuations are durably recorded on IO before returning to the Main caller. */
class DefaultBookDetailServiceSessionRepository(private val services:BookDetailAcceptedServicesRepository,
    private val details:BookDetailRepository,private val sessions:BookDetailSessionRepository,
    private val network:BookDetailNetworkRepository,private val io:CoroutineDispatcher=Dispatchers.IO):BookDetailServiceSessionRepository {
    override suspend fun execute(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest):BookDetailSession=withContext(io) {
        var current=record
        val active=current.pendingService?.request ?: request
        if(active.token in current.completedOperations)return@withContext current
        if(current.pendingService==null) {
            current=current.copy(pendingService=BookDetailPendingService(active),revision=current.revision+1)
            sessions.write(ticket,current)
        }
        val cached=current.pendingService?.result
        suspend fun accepted(value:BookDetailServiceResult) {
            withContext(NonCancellable) {
                val next=current.copy(pendingService=BookDetailPendingService(active,value),revision=current.revision+1)
                try{sessions.write(ticket,next)}catch(error:Exception){throw BookDetailServiceReceiptFailure(error)}
                current=next
            }
        }
        val result=cached ?: try {
            when(active.kind) {
                BookDetailServiceKind.Refresh->services.refreshInput(active.book,active.source).let{BookDetailServiceResult(book=it.book,warning=it.warning)}
                BookDetailServiceKind.ClearCache->{services.clearCache(active.book);BookDetailServiceResult()}
                BookDetailServiceKind.Delete->BookDetailServiceResult(book=services.delete(active.book,active.deleteOriginal,active.deleteRemote){accepted(BookDetailServiceResult(book=it))})
                BookDetailServiceKind.UploadCheck->BookDetailServiceResult(exists=services.remoteExists(active.book))
                BookDetailServiceKind.Upload->BookDetailServiceResult(book=services.upload(active.book,active.overwrite){accepted(BookDetailServiceResult(book=it))})
                BookDetailServiceKind.Download->services.download(active.book,active.source,checkNotNull(active.file)){accepted(BookDetailServiceResult(book=it.book,uri=it.uri))}.let{BookDetailServiceResult(book=it.book,uri=it.uri)}
                BookDetailServiceKind.ArchiveList->BookDetailServiceResult(entries=services.archiveEntries(checkNotNull(active.uri)))
                BookDetailServiceKind.ArchiveImport->BookDetailServiceResult(book=services.importArchive(active.book,checkNotNull(active.uri),checkNotNull(active.entry)){accepted(BookDetailServiceResult(book=it))})
            }
        }catch(error:Throwable) {
            currentCoroutineContext().ensureActive()
            if(error is BookDetailServiceReceiptFailure)throw error.cause ?: error
            if(error is BookDetailUploadConflict) {
                val next=current.copy(pendingService=null,prompt=BookDetailPrompt(BookDetailPromptKind.OverwriteUpload,
                    readAfter=active.readAfter),revision=current.revision+1)
                withContext(NonCancellable){sessions.write(ticket,next)}
                currentCoroutineContext().ensureActive();return@withContext next
            }
            // Existing imported-book upload completes its reading continuation even when the transport fails.
            if(active.kind==BookDetailServiceKind.Upload && active.readAfter)BookDetailServiceResult(warning=error.localizedMessage ?: error.toString())else throw error
        }
        // Mutating services transfer their result through accepted() inside their own IO commit boundary.
        // Read-only stages need only retain the successful response before continuing.
        withContext(NonCancellable) {
            if(current.pendingService?.result==null) {
                current=current.copy(pendingService=BookDetailPendingService(active,result),revision=current.revision+1)
                sessions.write(ticket,current)
            }
        }
        currentCoroutineContext().ensureActive()
        finish(ticket,current,active,result)
    }
    private suspend fun finish(ticket:String,record:BookDetailSession,request:BookDetailServiceRequest,result:BookDetailServiceResult):BookDetailSession {
        var current=record
        val effects=current.effects.toMutableList();var data=current.data;var prompt:BookDetailPrompt?=null
        fun native(kind:BookDetailNativeKind,value:String?=null,book:BookDetailBook?=data?.book,flag:Boolean=false) {
            val token=request.token+":"+kind.name
            if(effects.none{it.token==token})effects+=BookDetailNativeEffect(token,kind,book,data?.source,value,flag,
                expectedBookUrl=request.book.bookUrl)
        }
        when(request.kind) {
            BookDetailServiceKind.Refresh->{
                val infoToken=request.token+":info"
                if(infoToken !in current.completedOperations) {
                    val baseline=checkNotNull(current.data)
                    val parsed=network.info(checkNotNull(result.book),baseline.source,false,true)
                    currentCoroutineContext().ensureActive()
                    current=sessions.completeNetwork(ticket,current,baseline,parsed,false,infoToken)
                }
                data=current.data;effects.clear();effects+=current.effects
                result.warning?.let{native(BookDetailNativeKind.Toast,it)}
            }
            BookDetailServiceKind.ClearCache->native(BookDetailNativeKind.CacheCleared)
            BookDetailServiceKind.Delete->native(BookDetailNativeKind.Deleted,book=result.book ?: request.book,flag=true)
            BookDetailServiceKind.UploadCheck-> {
                if(result.exists)prompt=BookDetailPrompt(BookDetailPromptKind.OverwriteUpload,readAfter=request.readAfter)
                else {
                    val follow=request.copy(token=request.token+":upload",kind=BookDetailServiceKind.Upload,overwrite=false)
                    val prepared=current.copy(pendingService=BookDetailPendingService(follow),
                        completedOperations=(current.completedOperations+request.token).takeLast(64),revision=current.revision+1)
                    withContext(NonCancellable){sessions.write(ticket,prepared)}
                    currentCoroutineContext().ensureActive();return execute(ticket,prepared,follow)
                }
            }
            BookDetailServiceKind.Upload->{
                result.book?.let{data=details.describe(it,checkNotNull(current.data).inBookshelf);native(BookDetailNativeKind.ReaderSync)}
                result.warning?.let{native(BookDetailNativeKind.Toast,it)}
                if(request.readAfter)native(BookDetailNativeKind.Reader,flag=current.chapterChanged)
            }
            BookDetailServiceKind.Download,BookDetailServiceKind.ArchiveImport->{
                if(result.book!=null) {
                    // The existing import engine has already merged a local Room owner; use that new owner as the Toc baseline.
                    val importedData=details.reload(result.book.bookUrl) ?: throw BookDetailMissing()
                    data=importedData
                    val tocToken=request.token+":toc"
                    if(tocToken !in current.completedOperations) {
                        val chapters=network.toc(importedData.book,importedData.source,true,true)
                        currentCoroutineContext().ensureActive()
                        current=current.copy(data=data,revision=current.revision+1);sessions.write(ticket,current)
                        current=sessions.completeNetwork(ticket,current,importedData,chapters,false,tocToken)
                    }
                    if(tocToken in current.completedOperations && current.data?.book?.bookUrl==importedData.book.bookUrl) {
                        data=details.reload(importedData.book.bookUrl) ?: throw BookDetailMissing()
                        current=current.copy(data=data)
                    }
                    effects.clear();effects+=current.effects
                    if(request.uploadImported) {
                        val follow=request.copy(token=request.token+":upload-check",kind=BookDetailServiceKind.UploadCheck,
                            book=checkNotNull(data).book,source=checkNotNull(data).source,file=null,uri=null,entry=null)
                        val prepared=current.copy(pendingService=BookDetailPendingService(follow),
                            completedOperations=(current.completedOperations+request.token).takeLast(64),revision=current.revision+1)
                        withContext(NonCancellable){sessions.write(ticket,prepared)}
                        currentCoroutineContext().ensureActive();return execute(ticket,prepared,follow)
                    }else if(request.readAfter)native(BookDetailNativeKind.Reader,flag=current.chapterChanged)
                }else if(result.uri!=null) {
                    if(request.file?.archive==true) {
                        val follow=request.copy(token=request.token+":archive-list",kind=BookDetailServiceKind.ArchiveList,uri=result.uri)
                        val prepared=current.copy(pendingService=BookDetailPendingService(follow),
                            completedOperations=(current.completedOperations+request.token).takeLast(64),revision=current.revision+1)
                        withContext(NonCancellable){sessions.write(ticket,prepared)}
                        currentCoroutineContext().ensureActive();return execute(ticket,prepared,follow)
                    }else native(BookDetailNativeKind.OpenFile,result.uri)
                }
            }
            BookDetailServiceKind.ArchiveList->{
                if(result.entries.size==1) {
                    val follow=request.copy(token=request.token+":archive-import",kind=BookDetailServiceKind.ArchiveImport,entry=result.entries.single())
                    val prepared=current.copy(pendingService=BookDetailPendingService(follow),
                        completedOperations=(current.completedOperations+request.token).takeLast(64),revision=current.revision+1)
                    withContext(NonCancellable){sessions.write(ticket,prepared)}
                    currentCoroutineContext().ensureActive();return execute(ticket,prepared,follow)
                }else if(result.entries.isNotEmpty())prompt=BookDetailPrompt(BookDetailPromptKind.ArchiveEntries,value=request.uri,values=result.entries,
                    readAfter=request.readAfter,uploadImported=request.uploadImported)
                else native(BookDetailNativeKind.Toast,"unsupported_archive")
            }
        }
        val completed=current.copy(data=data,prompt=prompt,pendingService=null,effects=effects,
            completedOperations=(current.completedOperations+request.token).takeLast(64),revision=current.revision+1)
        withContext(NonCancellable){sessions.write(ticket,completed)}
        currentCoroutineContext().ensureActive();return completed
    }
}
