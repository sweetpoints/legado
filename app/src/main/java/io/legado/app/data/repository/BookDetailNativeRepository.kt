package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.*

/** Fresh, detached native entities are prepared on IO and remain outside Compose/SavedState UI state. */
data class BookDetailNativePayload(val effect:BookDetailNativeEffect,val book:Book?,val source:BookSource?,
    val highlights:List<BookHighlight> = emptyList(),val text:String?=null,
    val variable:BookDetailVariable?=null,val task:BookDetailUpdateTask?=null)
data class BookDetailNativeTexts(val sourceVariableComment:String,val bookVariableComment:String,val updateTaskName:String)
interface BookDetailNativeRepository {suspend fun prepare(effect:BookDetailNativeEffect,texts:BookDetailNativeTexts):BookDetailNativePayload}
class RoomBookDetailNativeRepository(private val database:AppDatabase=appDb,
    private val services:BookDetailServicesRepository=AppBookDetailServicesRepository(database),
    private val io:CoroutineDispatcher=Dispatchers.IO):BookDetailNativeRepository {
    override suspend fun prepare(effect:BookDetailNativeEffect,texts:BookDetailNativeTexts)=withContext(io) {
        val snapshot=effect.book?.materializeBook()
        val book=if(effect.kind==BookDetailNativeKind.ReaderSync && snapshot!=null) {
            database.bookDao.getBook(snapshot.bookUrl)?.also{fresh->
                if(fresh.bookUrl==snapshot.bookUrl && fresh.origin==snapshot.origin) {
                    fresh.infoHtml=snapshot.infoHtml;fresh.tocHtml=snapshot.tocHtml;fresh.downloadUrls=snapshot.downloadUrls
                }
            }
        }else snapshot
        val source=effect.source?.materializeSource()
        val highlights=if(effect.kind==BookDetailNativeKind.ReaderSync && book!=null)database.bookHighlightDao.getByBook(book.bookUrl).map{it.copy()}else emptyList()
        val text=when(effect.kind) {
            BookDetailNativeKind.Share->book?.let{"${it.bookUrl}#${GSON.toJson(it)}"}
            BookDetailNativeKind.CopyBookUrl->book?.bookUrl
            BookDetailNativeKind.CopyTocUrl->book?.tocUrl
            else->effect.value
        }
        val variable=when(effect.kind) {
            BookDetailNativeKind.SourceVariable->services.variable(checkNotNull(effect.book),effect.source,true,texts.sourceVariableComment)
            BookDetailNativeKind.BookVariable->services.variable(checkNotNull(effect.book),effect.source,false,texts.bookVariableComment)
            else->null
        }
        val task=if(effect.kind==BookDetailNativeKind.UpdateTask)services.updateTask(checkNotNull(effect.book),texts.updateTaskName)else null
        currentCoroutineContext().ensureActive()
        BookDetailNativePayload(effect,book,source,highlights,text,variable,task)
    }
}
