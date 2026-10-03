package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.*
import org.junit.Test

class BookMetadataEditorMergeTest {
    private fun input(book:Book,changed:Set<BookMetadataField> = BookMetadataField.entries.toSet())=BookMetadataInput(book.bookUrl,"Edited","Edited author",0,"https://cover","Edited intro",changed)
    @Test fun typeSelectionPreservesLocalAndAllUnrelatedBitsIncludingWebFileAndArchive() {
        val base=Book(bookUrl="book",type=BookType.image or BookType.local or BookType.webFile or BookType.archive or BookType.updateError or BookType.notShelf)
        val kept=BookType.local or BookType.webFile or BookType.archive or BookType.updateError or BookType.notShelf
        val mappings=listOf(BookType.text,BookType.audio,BookType.image,BookType.text,BookType.video)
        mappings.forEachIndexed{index,type->assertEquals(kept or type,mergeBookMetadata(base,input(base).copy(typeIndex=index)).type)}
        assertEquals(BookType.image or kept,base.type)
    }
    @Test fun legacyTypeZeroLocalAndWebDavBooksAcquireLocalBitOnlyWhenUserChangesType() {
        listOf(BookType.localTag,BookType.webDavTag+"file").forEach {origin->
            val book=Book(bookUrl="book",origin=origin,type=0)
            assertEquals(BookType.audio or BookType.local,mergeBookMetadata(book,input(book).copy(typeIndex=1)).type)
            assertEquals(0,mergeBookMetadata(book,input(book,emptySet())).type)
        }
    }
    @Test fun untouchedFieldsKeepConcurrentSourceMetadataAndReadingProgress() {
        val current=Book(bookUrl="book",name="Current",author="Current author",coverUrl="new source",customCoverUrl="current override",persistedCoverUrl="cached",intro="new intro",customIntro="current intro",type=BookType.audio,durChapterIndex=9,durChapterPos=42,group=123,order=77,variable="fresh")
        val result=mergeBookMetadata(current,input(current,setOf(BookMetadataField.Name)))
        assertEquals("Edited",result.name);assertEquals("Current author",result.author);assertEquals("current override",result.customCoverUrl);assertEquals("cached",result.persistedCoverUrl);assertEquals("current intro",result.customIntro);assertEquals(BookType.audio,result.type)
        assertEquals(9,result.durChapterIndex);assertEquals(42,result.durChapterPos);assertEquals(123L,result.group);assertEquals(77,result.order);assertEquals("fresh",result.variable)
    }
    @Test fun coverAndIntroMatchingSourceRemoveOverrideAndExplicitRefreshClearsPersistedCover() {
        val book=Book(bookUrl="book",coverUrl="https://cover",customCoverUrl="https://cover",persistedCoverUrl="cached",intro="Edited intro",customIntro="older")
        val same=mergeBookMetadata(book,input(book));assertNull(same.customCoverUrl);assertNull(same.customIntro);assertEquals("cached",same.persistedCoverUrl)
        assertNull(mergeBookMetadata(book,input(book).copy(refreshCover=true)).persistedCoverUrl)
        assertNull(mergeBookMetadata(book,input(book).copy(cover="new")).persistedCoverUrl)
    }
    @Test fun initialCoverAndIntroNeverExposePersistedCachePathAndPreviewTypeOrderMatchesLegacy() {
        val book=Book(bookUrl="book",coverUrl="network",persistedCoverUrl="private cached",intro="source",type=BookType.audio or BookType.image or BookType.video)
        val snapshot=BookMetadataSnapshot.from(book);assertEquals("network",snapshot.coverText);assertEquals("source",snapshot.introText);assertEquals(4,snapshot.typeIndex)
        assertEquals("custom",snapshot.copy(customCoverUrl="custom").coverText);assertEquals("custom intro",snapshot.copy(customIntro="custom intro").introText)
    }
}
