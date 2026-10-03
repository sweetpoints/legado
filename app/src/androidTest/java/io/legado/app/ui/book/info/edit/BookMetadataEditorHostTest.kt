package io.legado.app.ui.book.info.edit

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.model.ReadBook
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class BookMetadataEditorHostTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun intent(book:Book)=Intent(context,BookInfoEditActivity::class.java).putExtra("bookUrl",book.bookUrl)
    private fun saved(model:BookMetadataEditorViewModel)=BookMetadataEditorViewModel::class.java.getDeclaredField("saved").apply{isAccessible=true}.get(model) as SavedStateHandle
    @Test fun actualActivityRotatesDraftWithoutSavingLargeIntentAndReturnsOkWithLatestReaderProgressAndHighlightLabels()=runBlocking {
        val token=UUID.randomUUID().toString();val book=Book(bookUrl="metadata-$token-"+"large-url".repeat(1000),name="Original $token",author="Author",coverUrl="/missing",persistedCoverUrl="cached",type=BookType.text)
        val highlight=BookHighlight(bookUrl=book.bookUrl,bookName=book.name,bookAuthor=book.author,note="Preserve note")
        val previous=ReadBook.book;val previousHighlights=ReadBook.highlights.toList();var ticket:String?=null
        withContext(Dispatchers.IO){appDb.bookDao.insert(book);appDb.bookHighlightDao.insert(highlight)}
        try{ActivityScenario.launchActivityForResult<BookInfoEditActivity>(intent(book)).use{scenario->
            compose.waitUntil(timeoutMillis=5000){var loaded=false;scenario.onActivity{loaded=it.viewModel.state.value.loaded};loaded}
            scenario.onActivity{ticket=it.viewModel.ticket;assertFalse(saved(it.viewModel).contains("bookUrl"))}
            compose.onNodeWithTag("book-metadata-name").performTextReplacement("Edited $token")
            compose.onNodeWithTag("book-metadata-author").performTextReplacement("Edited author")
            compose.onNodeWithTag("book-metadata-intro").performScrollTo().performTextReplacement("Multiline\nDraft introduction")
            scenario.recreate()
            compose.waitUntil(timeoutMillis=5000){var loaded=false;scenario.onActivity{loaded=it.viewModel.state.value.loaded};loaded}
            compose.onNodeWithTag("book-metadata-intro").performScrollTo().assertTextContains("Multiline\nDraft introduction")
            withContext(Dispatchers.IO){appDb.bookDao.insert(book.copy(durChapterIndex=9,durChapterPos=42,variable="Fresh Room variable"))}
            scenario.onActivity{assertFalse(saved(it.viewModel).contains("bookUrl"));ReadBook.book=book.copy(durChapterIndex=15,durChapterPos=67,variable="Live reader variable");it.coverChangeTo("use_default_cover")}
            compose.waitUntil(timeoutMillis=5000){var received=false;scenario.onActivity{received=it.viewModel.state.value.draft?.input?.cover=="use_default_cover"};received}
            compose.onNodeWithTag("book-metadata-save").performClick();assertEquals(Activity.RESULT_OK,scenario.result.resultCode)
            val actual=withContext(Dispatchers.IO){appDb.bookDao.getBook(book.bookUrl)!!};assertEquals("Edited $token",actual.name);assertEquals(9,actual.durChapterIndex);assertEquals(42,actual.durChapterPos);assertEquals("Fresh Room variable",actual.variable);assertNull(actual.persistedCoverUrl)
            assertEquals("Multiline\nDraft introduction",actual.customIntro);assertEquals("use_default_cover",actual.customCoverUrl)
            val reader=ReadBook.book!!;assertEquals(actual.name,reader.name);assertEquals(actual.author,reader.author);assertEquals(15,reader.durChapterIndex);assertEquals(67,reader.durChapterPos);assertEquals("Live reader variable",reader.variable)
            val label=ReadBook.highlights.single{it.bookUrl==book.bookUrl};assertEquals(actual.name,label.bookName);assertEquals(actual.author,label.bookAuthor);assertEquals("Preserve note",label.note)
            withTimeout(5000){while(withContext(Dispatchers.IO){File(context.filesDir,"book-metadata-editor/$ticket.json").exists()})delay(10)}
        }}finally{withContext(Dispatchers.Main){val owner=previous ?: Book(bookUrl="test-cleanup-owner");ReadBook.book=owner;check(ReadBook.applyPreparedHighlights(owner.bookUrl,previousHighlights));ReadBook.book=previous};withContext(Dispatchers.IO){appDb.bookDao.delete(book);appDb.bookHighlightDao.delete(highlight);cleanup(ticket)}}
    }
    @Test fun nativeBackCancelsWithoutWritingRoomOrReaderAndCleansOnlyOwnedDraft()=runBlocking {
        val book=Book(bookUrl="metadata-back-${UUID.randomUUID()}",name="Back ${UUID.randomUUID()}",author="Author",coverUrl="/missing");var ticket:String?=null
        withContext(Dispatchers.IO){appDb.bookDao.insert(book)}
        try{ActivityScenario.launchActivityForResult<BookInfoEditActivity>(intent(book)).use{scenario->
            compose.waitUntil(timeoutMillis=5000){var loaded=false;scenario.onActivity{loaded=it.viewModel.state.value.loaded};loaded}
            scenario.onActivity{ticket=it.viewModel.ticket;it.viewModel.text(io.legado.app.data.repository.BookMetadataField.Name,"Discard this")}
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};assertEquals(Activity.RESULT_CANCELED,scenario.result.resultCode)
            assertEquals(book.name,withContext(Dispatchers.IO){appDb.bookDao.getBook(book.bookUrl)!!.name})
            withTimeout(5000){while(withContext(Dispatchers.IO){File(context.filesDir,"book-metadata-editor/$ticket.json").exists()})delay(10)}
            assertTrue(File(context.filesDir,"book-metadata-editor/$ticket.closed").exists())
        }}finally{withContext(Dispatchers.IO){appDb.bookDao.delete(book);cleanup(ticket)}}
    }
    private fun cleanup(ticket:String?){ticket?.let{listOf("json","json.bak","json.new","closed","closed.bak","closed.new").forEach{suffix->File(context.filesDir,"book-metadata-editor/$ticket.$suffix").delete()}}}
}
