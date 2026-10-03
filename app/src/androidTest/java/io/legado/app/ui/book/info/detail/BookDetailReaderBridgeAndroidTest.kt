package io.legado.app.ui.book.info.detail

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.*
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookDetailReaderBridgeAndroidTest {
    private suspend fun withReaders(block:suspend()->Unit) {
        val old=withContext(Dispatchers.Main){Triple(ReadBook.book,ReadBook.highlights.toList(),AudioPlay.book)}
        try{block()}finally{withContext(Dispatchers.Main){
            ReadBook.book=old.first ?: Book(bookUrl="test-restore")
            check(ReadBook.applyPreparedHighlights(ReadBook.book!!.bookUrl,old.second))
            ReadBook.book=old.first;AudioPlay.book=old.third
        }}
    }
    private fun payload(book:Book,expected:String)=BookDetailNativePayload(
        BookDetailNativeEffect("sync",BookDetailNativeKind.ReaderSync,BookDetailBook.from(book),expectedBookUrl=expected),book,null,
        listOf(BookHighlight(time=1,bookUrl=book.bookUrl,note="Prepared")))
    @Test fun oldUrlOwnerReceivesChangedMetadataAndPreparedHighlightsWhileLiveProgressRemainsFresh()=runBlocking {
        withReaders{withContext(Dispatchers.Main){
            val config=Book.ReadConfig(pageAnim=3);ReadBook.book=Book(bookUrl="old",name="Old",durChapterIndex=17,durChapterPos=92,readConfig=config)
            val prepared=payload(Book(bookUrl="new",name="Renamed",intro="Intro"),"old")
            assertTrue(applyBookDetailReaderPayload(prepared));assertEquals("new",ReadBook.book!!.bookUrl)
            assertEquals("Renamed",ReadBook.book!!.name);assertEquals(17,ReadBook.book!!.durChapterIndex);assertEquals(92,ReadBook.book!!.durChapterPos)
            assertSame(config,ReadBook.book!!.readConfig);assertEquals("Prepared",ReadBook.highlights.single().note)
        }}
    }
    @Test fun switchingToAnotherBookAfterPreparationRejectsPayloadWithoutReplacingItsHighlightsOrAudioOwner()=runBlocking {
        val prepared=withContext(Dispatchers.IO){payload(Book(bookUrl="new",name="Same name"),"old")}
        withReaders{withContext(Dispatchers.Main){
            ReadBook.book=Book(bookUrl="other",name="Same name",durChapterIndex=11)
            val highlights=listOf(BookHighlight(time=2,bookUrl="other",note="Other owner"));ReadBook.applyPreparedHighlights("other",highlights)
            AudioPlay.book=Book(bookUrl="other-audio",name="Same name",durChapterIndex=8)
            assertFalse(applyBookDetailReaderPayload(prepared));assertEquals("other",ReadBook.book!!.bookUrl)
            assertEquals(highlights,ReadBook.highlights);assertEquals("other-audio",AudioPlay.book!!.bookUrl)
        }}
    }
    @Test fun splitLongReceiptUpdatesOnlyRequestedConfigAndKeepsCurrentPositionAndOtherSettings()=runBlocking {
        withReaders{withContext(Dispatchers.Main){
            val config=Book.ReadConfig(pageAnim=3,splitLongChapter=true,manualReplaceRuleIds=listOf(7L))
            ReadBook.book=Book(bookUrl="book",durChapterIndex=11,readConfig=config)
            val committed=Book(bookUrl="book",name="Updated",readConfig=Book.ReadConfig(splitLongChapter=false))
            val base=payload(committed,"book");val changed=base.copy(effect=base.effect.copy(mutation=BookDetailMutationKind.SplitLong))
            assertTrue(applyBookDetailReaderPayload(changed));assertFalse(ReadBook.book!!.readConfig!!.splitLongChapter)
            assertEquals(3,ReadBook.book!!.readConfig!!.pageAnim);assertEquals(listOf(7L),ReadBook.book!!.readConfig!!.manualReplaceRuleIds)
            assertEquals(11,ReadBook.book!!.durChapterIndex);assertTrue(config.splitLongChapter)
        }}
    }
    @Test fun publicationMustRunOnMainAndAudioSameOwnerKeepsItsNewPositionAndSpeed()=runBlocking {
        val prepared=withContext(Dispatchers.IO){payload(Book(bookUrl="audio",name="Updated"),"audio")}
        assertTrue(withContext(Dispatchers.IO){runCatching{applyBookDetailReaderPayload(prepared)}.isFailure})
        withReaders{withContext(Dispatchers.Main){
            ReadBook.book=null;val config=Book.ReadConfig(playSpeed=1.8f)
            AudioPlay.book=Book(bookUrl="audio",name="Old",durChapterIndex=7,durChapterPos=44,readConfig=config)
            assertTrue(applyBookDetailReaderPayload(prepared));assertEquals("Updated",AudioPlay.book!!.name)
            assertEquals(7,AudioPlay.book!!.durChapterIndex);assertEquals(44,AudioPlay.book!!.durChapterPos);assertSame(config,AudioPlay.book!!.readConfig)
        }}
    }
}
