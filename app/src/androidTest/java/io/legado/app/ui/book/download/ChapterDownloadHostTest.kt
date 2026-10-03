package io.legado.app.ui.book.download

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.Book
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ChapterDownloadHostTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun actualDialogKeepsHugeBookPrivateAndRestoresInputsBeforeCancelWithoutStartingService()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();var ticket:String?=null
        val intent=Intent(context,ConfigActivity::class.java).putExtra("configTag",ConfigTag.COVER_CONFIG)
        try{ActivityScenario.launch<ConfigActivity>(intent).use{scenario->
            scenario.onActivity{it.showChapterDownloadDialog(Book(bookUrl="B".repeat(300000),name="Draft",variable="V".repeat(1500000),durChapterIndex=3,totalChapterNum=17))}
            compose.waitUntil(timeoutMillis=10000){compose.onAllNodesWithTag("chapter-download-start").fetchSemanticsNodes().isNotEmpty()}
            scenario.onActivity{val dialog=it.supportFragmentManager.findFragmentByTag("chapter-download")!!;assertEquals(setOf("chapter-download-ticket"),dialog.requireArguments().keySet());ticket=dialog.requireArguments().getString("chapter-download-ticket");assertEquals(36,ticket!!.length)}
            compose.onNodeWithTag("chapter-download-start").performTextReplacement("");compose.onNodeWithTag("chapter-download-end").performTextReplacement("12345")
            scenario.recreate();compose.waitUntil(timeoutMillis=10000){compose.onAllNodesWithTag("chapter-download-end").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("chapter-download-start").assertTextContains("");compose.onNodeWithTag("chapter-download-end").assertTextContains("12345")
            compose.onNodeWithTag("chapter-download-cancel").performClick()
            compose.waitUntil(timeoutMillis=10000){var gone=false;scenario.onActivity{gone=it.supportFragmentManager.findFragmentByTag("chapter-download")==null};gone}
            withTimeout(10000){while(withContext(Dispatchers.IO){File(context.filesDir,"chapter-download-sessions/$ticket.json").exists()})delay(10)}
            assertTrue(File(context.filesDir,"chapter-download-sessions/$ticket.json.closed").exists())
        }}finally{withContext(Dispatchers.IO){ticket?.let{id->listOf("json","json.bak","json.new","json.closed","json.closed.bak","json.closed.new").forEach{suffix->File(context.filesDir,"chapter-download-sessions/$id.$suffix").delete()}}}}
    }
}
