package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BookMetadataEditorUiTest {
    @get:Rule val compose=createComposeRule()
    private val book=BookMetadataSnapshot("book","Original","Author",8,"source","source-cover",null,"cached-cover","intro",null)
    private fun initial()=BookMetadataEditorState(draft=BookMetadataDraft(book.bookUrl,book,
        BookMetadataInput(book.bookUrl,book.name,book.author,book.typeIndex,book.coverText,book.introText),book.preview()),loading=false,loaded=true)
    @Test fun fiveTypeEntriesPreserveIndexAndCoverTextDoesNotChangePreviewUntilRefresh() {
        var state by mutableStateOf(initial());var saves=0
        val actions=BookMetadataEditorActions(save={saves++},type={index->state=state.copy(draft=state.draft!!.copy(input=state.draft!!.input!!.copy(typeIndex=index)))},
            text={field,text,start,end->if(field==BookMetadataField.Cover)state=state.copy(draft=state.draft!!.copy(input=state.draft!!.input!!.copy(cover=text)),cursors=mapOf(field to BookMetadataCursor(start,end)))},
            refreshCover={state=state.copy(draft=state.draft!!.copy(preview=book.preview(state.draft!!.input!!.cover,null)))})
        compose.setContent{LegadoComposeTheme{BookMetadataEditorScreen(state,actions,cover={request,modifier->Text(request.path.orEmpty(),modifier)})}}
        for(index in 0..4){compose.onNodeWithTag("book-metadata-type").performScrollTo().performClick();compose.onNodeWithTag("book-metadata-type-$index").performClick();assertEquals(index,state.draft!!.input!!.typeIndex)}
        compose.onNodeWithTag("book-metadata-cover").performScrollTo().performTextReplacement("new-cover")
        assertEquals("cached-cover",state.draft!!.preview!!.path);assertEquals(0,saves)
        compose.onNodeWithTag("book-metadata-refresh-cover").performScrollTo().performClick();assertEquals("new-cover",state.draft!!.preview!!.path)
        compose.onNodeWithTag("book-metadata-save").performClick();assertEquals(1,saves)
    }
    @Test fun cursorAndTypePopupRestoreWithoutChangingTextAndMultilineIntroRestoresScroll() {
        val tester=StateRestorationTester(compose);var state by mutableStateOf(initial());var edits=0
        val actions=BookMetadataEditorActions(text={field,text,start,end->assertEquals(state.draft!!.input!!.name,text);edits++;state=state.copy(cursors=mapOf(field to BookMetadataCursor(start,end)))})
        tester.setContent{LegadoComposeTheme{Box(Modifier.size(320.dp,300.dp)){BookMetadataEditorScreen(state,actions,cover={_,modifier->Box(modifier)})}}}
        compose.onNodeWithTag("book-metadata-name").performScrollTo().performTextInputSelection(TextRange(2,5));val before=edits
        compose.onNodeWithTag("book-metadata-type").performScrollTo().performClick();tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("book-metadata-type-3").assertExists();assertEquals(before,edits)
        compose.onNodeWithTag("book-metadata-type-3").performClick();compose.onNodeWithTag("book-metadata-name").performScrollTo()
        assertEquals(TextRange(2,5),compose.onNodeWithTag("book-metadata-name").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("book-metadata-intro").performScrollTo().assertIsDisplayed();tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("book-metadata-intro").assertIsDisplayed();compose.onNodeWithTag("book-metadata-save").assertIsDisplayed()
    }
    @Test fun darkConstrainedScreenKeepsSaveVisibleAndAllCoverActionsHaveAccessibleTouchBounds() {
        var density=1f
        compose.setContent{density=LocalDensity.current.density;MaterialTheme(colorScheme=darkColorScheme()){Box(Modifier.size(320.dp,300.dp)){BookMetadataEditorScreen(initial(),BookMetadataEditorActions(),cover={_,modifier->Text("Visible cover",modifier)})}}}
        compose.onNodeWithTag("book-metadata-refresh-cover").performScrollTo().assertIsDisplayed()
        listOf("pick-cover","change-cover","refresh-cover").forEach{suffix->val bounds=compose.onNodeWithTag("book-metadata-$suffix").fetchSemanticsNode().boundsInRoot;assertTrue(bounds.height>=48*density-1);assertTrue(bounds.width>0)}
        compose.onNodeWithTag("book-metadata-intro").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("book-metadata-save").assertIsDisplayed()
    }
    @Test fun failedSaveDisablesEditingAndOffersExplicitRetryOrReloadWhilePreservingDraft() {
        var retries=0;var reloads=0;val initial=initial();val draft=initial.draft!!;val state=initial.copy(saveFailed=true,error="Another host changed metadata",draft=draft.copy(input=draft.input!!.copy(name="Unsaved draft")))
        compose.setContent{LegadoComposeTheme{BookMetadataEditorScreen(state,BookMetadataEditorActions(retry={retries++},reload={reloads++}),cover={_,modifier->Box(modifier)})}}
        compose.onNodeWithTag("book-metadata-name").assertTextContains("Unsaved draft").assertIsNotEnabled();compose.onNodeWithTag("book-metadata-save").assertIsNotEnabled()
        compose.onNodeWithTag("book-metadata-retry").performClick();compose.onNodeWithTag("book-metadata-reload").performClick();assertEquals(1,retries);assertEquals(1,reloads)
    }
}
