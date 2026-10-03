package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.*
import org.junit.Test

class BookDetailNetworkMergeTest {
    private fun book()=Book(bookUrl="original",name="Name",author="Author",origin="source",group=1,
        variable="{\"existing\":\"old\"}",customCoverUrl="custom",durChapterPos=10)
    @Test fun intentionalScriptChangesApplyWhileUntouchedProgressAndConcurrentUserMetadataStayFresh() {
        val request=book()
        val parsed=request.copy(bookUrl="changed",tocUrl="changed/toc",group=7,canUpdate=false,
            variable="{\"existing\":\"script\",\"new\":\"script\"}")
        parsed.infoHtml="private cached HTML";parsed.downloadUrls=listOf("file")
        val latest=request.copy(durChapterPos=91,customCoverUrl="new custom",persistedCoverUrl="new cache",
            variable="{\"existing\":\"external\",\"external\":\"keep\"}")
        val actual=mergeBookDetailNetworkMetadata(request,parsed,latest)
        assertEquals("changed",actual.bookUrl);assertEquals(7L,actual.group);assertFalse(actual.canUpdate)
        assertEquals(91,actual.durChapterPos);assertEquals("new custom",actual.customCoverUrl)
        assertEquals("new cache",actual.persistedCoverUrl);assertEquals("external",actual.getVariable("existing"))
        assertEquals("script",actual.getVariable("new"));assertEquals("keep",actual.getVariable("external"))
        assertEquals("private cached HTML",actual.infoHtml);assertEquals(listOf("file"),actual.downloadUrls)
        assertEquals("original",request.bookUrl);assertEquals(10,request.durChapterPos)
    }
    @Test fun simultaneousChangesToSameFieldKeepLatestStoredValueAndIndependentTypeFlags() {
        val request=book().copy(type=BookType.text)
        val parsed=request.copy(group=5,type=BookType.audio)
        val latest=request.copy(group=9,type=BookType.text or BookType.archive)
        val actual=mergeBookDetailNetworkMetadata(request,parsed,latest)
        assertEquals(9L,actual.group);assertEquals(BookType.audio or BookType.archive,actual.type)
    }
    @Test fun newRuleVariablesMergeWithConcurrentVariablesWhenRequestHasNoVariableObject() {
        val request=book().copy(variable=null)
        val parsed=request.copy(variable="{\"script\":\"new\"}")
        val latest=request.copy(variable="{\"external\":\"new\"}")
        val result=mergeBookDetailNetworkMetadata(request,parsed,latest)
        assertEquals("new",result.getVariable("script"));assertEquals("new",result.getVariable("external"))
    }
    @Test fun scriptVariableDeletionDoesNotDeleteConcurrentReplacementAndMalformedOpaqueTextIsPreserved() {
        val request=book();val parsed=request.copy(variable="{}")
        assertNull(mergeBookDetailNetworkMetadata(request,parsed,request).variableMap["existing"])
        val latest=request.copy(variable="{\"existing\":\"external\"}")
        assertEquals("external",mergeBookDetailNetworkMetadata(request,parsed,latest).getVariable("existing"))
        assertEquals("opaque external",mergeBookDetailNetworkMetadata(request,parsed,request.copy(variable="opaque external")).variable)
    }
}
