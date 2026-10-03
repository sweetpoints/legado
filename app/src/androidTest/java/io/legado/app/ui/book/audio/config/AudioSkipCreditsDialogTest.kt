package io.legado.app.ui.book.audio.config

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.ui.about.AboutActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class AudioSkipCreditsDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun rotationKeepsDraftWithoutSavingAndNativeDismissPersistsOncePreservingCoverAndOtherConfig() = runBlocking {
        val book = Book(bookUrl = "https://audio-skip/${UUID.randomUUID()}", origin = "https://source", name = "Book", author = "Author", customCoverUrl = "original-cover")
        book.config.apply { useGlobalAudioSkip = false; openCredits = 12; closeCredits = 34; playMode = 2 }
        withContext(Dispatchers.IO) { appDb.bookDao.insert(book) }
        try { ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { AudioSkipCredits.newInstance(book).show(it.supportFragmentManager, "audio-skip") }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("audio-skip-opening-plus").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("audio-skip-opening-plus").performClick()
            compose.onNodeWithTag("audio-skip-opening-value").assertTextEquals("13")
            compose.runOnIdle { assertEquals(13, book.config.openCredits) }
            assertEquals(12, withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!!.config.openCredits })
            scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("audio-skip-opening-value").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("audio-skip-opening-value").assertTextEquals("13")
            assertEquals(12, withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!!.config.openCredits })
            withContext(Dispatchers.IO) { val latest = appDb.bookDao.getBook(book.bookUrl)!!; latest.customCoverUrl = "new-cover"; latest.durChapterIndex = 8; latest.config.playMode = 3; appDb.bookDao.insert(latest) }
            scenario.onActivity { (it.supportFragmentManager.findFragmentByTag("audio-skip") as AudioSkipCredits).dismiss() }
            val saved = withTimeout(5000) { while (true) {
                val value = withContext(Dispatchers.IO) { appDb.bookDao.getBook(book.bookUrl)!! }
                if (value.config.openCredits == 13) return@withTimeout value
                delay(25)
            }; error("unreachable") }
            assertEquals(34, saved.config.closeCredits); assertEquals("new-cover", saved.customCoverUrl)
            assertEquals(8, saved.durChapterIndex); assertEquals(3, saved.config.playMode)
        } } finally { withContext(Dispatchers.IO) { appDb.bookDao.delete(book) } }
    }
}
