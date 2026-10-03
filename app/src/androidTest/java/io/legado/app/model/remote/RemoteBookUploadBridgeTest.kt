package io.legado.app.model.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.lib.webdav.Authorization
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RemoteBookUploadBridgeTest {
    @Test fun actualConditionalPutWithoutPersistLeavesFreshRoomProgressUntouchedWhileLegacyApiStillPersistsOrigin()=runBlocking {
        assumeTrue("The production WebDAV bridge requires an active network",NetworkUtils.isAvailable())
        val context=ApplicationProvider.getApplicationContext<Context>()
        val local=File(context.cacheDir,"bridge-${UUID.randomUUID()}.txt").apply{writeText("fixture contents")}
        val old=Book(bookUrl=local.absolutePath,name="Bridge-${UUID.randomUUID()}",originName=local.name,
            type=BookType.local or BookType.text,durChapterPos=1)
        val putHeader=AtomicReference<String?>();val uploadedBody=AtomicReference<String?>();val puts=java.util.concurrent.atomic.AtomicInteger()
        val server=object:NanoHTTPD("127.0.0.1",0) {
            override fun serve(session:IHTTPSession):Response {
                if(session.method==Method.PUT) {
                    puts.incrementAndGet();putHeader.set(session.headers["if-none-match"])
                    val files=mutableMapOf<String,String>();session.parseBody(files)
                    uploadedBody.set(files["content"]?.let{File(it).readText()})
                }
                return newFixedLengthResponse(Response.Status.OK,"text/plain","")
            }
        }.apply{start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)}
        try {
            withContext(Dispatchers.IO){appDb.bookDao.insert(old.copy(durChapterPos=99))}
            val manager=withContext(Dispatchers.IO){RemoteBookWebDav("http://127.0.0.1:${server.listeningPort}/books/",Authorization("fixture","fixture"),serverID=7)}
            withContext(Dispatchers.IO){manager.uploadWithoutPersist(old,false)}
            assertEquals("*",putHeader.get());assertEquals("fixture contents",uploadedBody.get());assertEquals(1,puts.get())
            val fresh=withContext(Dispatchers.IO){appDb.bookDao.getBook(old.bookUrl)!!}
            assertEquals(99,fresh.durChapterPos);assertEquals(BookType.localTag,fresh.origin)
            assertTrue(old.origin.startsWith(BookType.webDavTag))
            withContext(Dispatchers.IO){manager.upload(fresh,true)}
            assertNull(putHeader.get());assertEquals(2,puts.get())
            val persisted=withContext(Dispatchers.IO){appDb.bookDao.getBook(old.bookUrl)!!}
            assertEquals(fresh.origin,persisted.origin);assertEquals(99,persisted.durChapterPos)
        }finally{withContext(Dispatchers.IO){appDb.bookDao.delete(old)};server.stop();local.delete()}
    }
    @Test fun actualConditionalPutConflictDoesNotMutateOriginOrPersistRoom()=runBlocking {
        assumeTrue("The production WebDAV bridge requires an active network",NetworkUtils.isAvailable())
        val context=ApplicationProvider.getApplicationContext<Context>();val local=File(context.cacheDir,"conflict-${UUID.randomUUID()}.txt").apply{writeText("contents")}
        val old=Book(bookUrl=local.absolutePath,name="Conflict-${UUID.randomUUID()}",originName=local.name,type=BookType.local or BookType.text)
        val sawConditional=AtomicBoolean()
        val server=object:NanoHTTPD("127.0.0.1",0) {
            override fun serve(session:IHTTPSession):Response {
                if(session.method==Method.PUT){sawConditional.set(session.headers["if-none-match"]=="*");session.parseBody(mutableMapOf());return newFixedLengthResponse(Response.Status.PRECONDITION_FAILED,"text/plain","conflict")}
                return newFixedLengthResponse(Response.Status.OK,"text/plain","")
            }
        }.apply{start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)}
        try {
            withContext(Dispatchers.IO){appDb.bookDao.insert(old)}
            val manager=withContext(Dispatchers.IO){RemoteBookWebDav("http://127.0.0.1:${server.listeningPort}/books/",Authorization("fixture","fixture"))}
            assertTrue(runCatching{withContext(Dispatchers.IO){manager.uploadWithoutPersist(old,false)}}.isFailure)
            assertTrue(sawConditional.get());assertEquals(BookType.localTag,old.origin)
            assertEquals(BookType.localTag,withContext(Dispatchers.IO){appDb.bookDao.getBook(old.bookUrl)!!.origin})
        }finally{withContext(Dispatchers.IO){appDb.bookDao.delete(old)};server.stop();local.delete()}
    }
}
