package io.legado.app.ui.book.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.model.download.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class ChapterDownloadScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun rangeInputsAndActionsAreRealComposeControlsWithIndependentEmptyDrafts(){
        var state by mutableStateOf(ChapterDownloadState(loaded=true,start="4",end="17"));var submitted=0;var cancelled=0
        compose.setContent{LegadoComposeTheme{ChapterDownloadScreen(state,{state=state.copy(start=it)},{state=state.copy(end=it)},{submitted++},{cancelled++},{})}}
        compose.onNodeWithTag("chapter-download-start").performTextReplacement("");compose.onNodeWithTag("chapter-download-end").performTextReplacement("99999")
        compose.onNodeWithTag("chapter-download-start").assertTextContains("");compose.onNodeWithTag("chapter-download-end").assertTextContains("99999")
        compose.onNodeWithTag("chapter-download-confirm").performClick();assertEquals(1,submitted)
        compose.onNodeWithTag("chapter-download-cancel").performClick();assertEquals(1,cancelled)
    }
    @Test fun darkConstrainedAudioDialogScrollsToActionsAndShowsInvalidRange(){
        compose.setContent{MaterialTheme(colorScheme=darkColorScheme()){Box(Modifier.size(320.dp,260.dp)){ChapterDownloadScreen(ChapterDownloadState(loaded=true,mode=ChapterDownloadMode.Audio,start="",end="",invalidRange=true),{},{},{},{},{})}}}
        compose.onNodeWithTag("chapter-download-invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chapter-download-confirm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chapter-download-cancel").assertIsDisplayed()
    }
    @Test fun busyReceiptDisablesBothEditorsAndConfirmationAndCancel(){
        compose.setContent{LegadoComposeTheme{ChapterDownloadScreen(ChapterDownloadState(loaded=true,busy=true,start="3",end="5"),{},{},{},{},{})}}
        listOf("start","end","confirm","cancel").forEach{compose.onNodeWithTag("chapter-download-$it").assertIsNotEnabled()}
    }
}
