package io.legado.app.ui.book.toc

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.TocChapterNavigation
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.postEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TocChapterHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = UUID.randomUUID().toString()
    private val book =
        Book(
            bookUrl = "fixture://${UUID.randomUUID()}",
            name = "ChapterFixture",
            origin = "remote",
            totalChapterNum = 3,
            durChapterIndex = 1,
            durChapterTitle = "Current",
        )
    private val chapters =
        (0..2).map {
            BookChapter(
                url = "${book.bookUrl}/$it",
                bookUrl = book.bookUrl,
                index = it,
                title = "Chapter $it",
            )
        }
    private lateinit var scenario: ActivityScenario<AboutActivity>
    private lateinit var host: ChapterListFragment

    @Before
    fun setup() {
        runBlocking(Dispatchers.IO) {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
        }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario.onActivity {
            ViewModelProvider(it)[TocViewModel::class.java].bookData.value = book
            host =
                ChapterListFragment().apply {
                    arguments = Bundle().apply { putString("tocChapter.session", session) }
                }
            it.supportFragmentManager
                .beginTransaction()
                .add(android.R.id.content, host, "toc-chapter")
                .commitNow()
        }
        compose.waitUntil { host.model.state.value.loaded }
    }

    @After
    fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) {
            withTimeout(5000) {
                while (
                    !File(context.filesDir, "toc-chapter-state/$session.released").exists()
                ) delay(10)
            }
            appDb.bookDao.delete(book)
        }
        AtomicFile(File(context.filesDir, "toc-chapter-state/$session.json")).delete()
        AtomicFile(File(context.filesDir, "toc-chapter-state/$session.released")).delete()
    }

    @Test
    fun sharedSearchAndConfigurationRebuildKeepOneCallbackAndRestoreExactCurrentRows() {
        scenario.onActivity {
            val shared = ViewModelProvider(it)[TocViewModel::class.java]
            shared.searchKey = "Chapter 2"
            shared.startChapterListSearch(shared.searchKey)
            assertSame(host, shared.chapterListCallBack)
        }
        compose.waitUntil { host.model.state.value.rows.map { it.index } == listOf(2) }
        compose
            .onNodeWithTag("toc-chapter-title-chapter:2", useUnmergedTree = true)
            .assertTextEquals("Chapter 2")
        scenario.recreate()
        scenario.onActivity {
            host = it.supportFragmentManager.findFragmentByTag("toc-chapter") as ChapterListFragment
            assertSame(host, ViewModelProvider(it)[TocViewModel::class.java].chapterListCallBack)
        }
        compose.waitUntil {
            host.model.state.value.loaded &&
                host.model.state.value.rows.map { it.index } == listOf(2)
        }
        assertEquals("Chapter 2", host.model.state.value.query)
    }

    @Test
    fun realSaveContentEventUpdatesCacheAndRemovedViewPreservesAnotherOwnersCallback() {
        compose.waitUntil {
            host.model.state.value.rows.any { it.key == "chapter:2" && !it.cached }
        }
        scenario.onActivity { postEvent(EventBus.SAVE_CONTENT, book to chapters[2]) }
        compose.waitUntil { host.model.state.value.rows.any { it.key == "chapter:2" && it.cached } }
        scenario.onActivity {
            val shared = ViewModelProvider(it)[TocViewModel::class.java]
            val another =
                object : TocViewModel.ChapterListCallBack {
                    override fun upChapterList(
                        searchKey: String?,
                        resetCollapse: Boolean,
                        replaceAll: Boolean,
                    ) {}

                    override fun clearDisplayTitle() {}

                    override fun upAdapter() {}
                }
            shared.chapterListCallBack = another
            it.supportFragmentManager.beginTransaction().remove(host).commitNow()
            assertSame(another, shared.chapterListCallBack)
        }
    }

    @Test
    fun nativeResultIntentKeepsTextPdfAndVideoMetadataWithoutExtraProtocolChanges() {
        val text = chapterResultIntent(TocChapterNavigation(2, true))
        assertEquals(2, text.getIntExtra("index", -1))
        assertTrue(text.getBooleanExtra("chapterChanged", false))
        assertFalse(text.hasExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX))
        val pdf = chapterResultIntent(TocChapterNavigation(2, false, pdfPage = 27))
        assertEquals(27, pdf.getIntExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX, -1))
        assertFalse(pdf.getBooleanExtra("chapterChanged", true))
        val video =
            chapterResultIntent(TocChapterNavigation(8, true, volumeIndex = 2, chapterInVolume = 3))
        assertEquals(2, video.getIntExtra("durVolumeIndex", -1))
        assertEquals(3, video.getIntExtra("chapterInVolumeIndex", -1))
    }
}
