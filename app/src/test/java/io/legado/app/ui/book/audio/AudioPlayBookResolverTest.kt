package io.legado.app.ui.book.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayBookResolverTest {

    @Test
    fun `existing audio screen is reused for notification and task entry`() {
        assertTrue(shouldReuseCurrentAudioPlay(null, "book-a"))
        assertTrue(shouldReuseCurrentAudioPlay("book-a", "book-a"))
        assertFalse(shouldReuseCurrentAudioPlay("book-b", "book-a"))
        assertFalse(shouldReuseCurrentAudioPlay("book-a", null))
    }

    @Test
    fun `requested book is loaded instead of another cached book`() {
        val cachedBook = TestBook("book-b")
        val databaseBook = TestBook("book-a")

        val result =
            resolveAudioPlayBook(
                requestedBookUrl = "book-a",
                cachedBook = cachedBook,
                bookUrlOf = TestBook::bookUrl,
                findBook = { databaseBook },
            )

        assertSame(databaseBook, result)
    }

    @Test
    fun `matching cached book avoids database lookup`() {
        val cachedBook = TestBook("book-a")
        var databaseLookupCount = 0

        val result =
            resolveAudioPlayBook(
                requestedBookUrl = "book-a",
                cachedBook = cachedBook,
                bookUrlOf = TestBook::bookUrl,
                findBook = {
                    databaseLookupCount++
                    TestBook("book-a")
                },
            )

        assertSame(cachedBook, result)
        assertEquals(0, databaseLookupCount)
    }

    @Test
    fun `notification restore without extras uses current cached book`() {
        val cachedBook = TestBook("book-a")

        val result =
            resolveAudioPlayBook(
                requestedBookUrl = null,
                cachedBook = cachedBook,
                bookUrlOf = TestBook::bookUrl,
                findBook = { error("database lookup should not run") },
            )

        assertSame(cachedBook, result)
    }

    @Test
    fun `missing requested book never falls back to another cached book`() {
        val result =
            resolveAudioPlayBook(
                requestedBookUrl = "book-a",
                cachedBook = TestBook("book-b"),
                bookUrlOf = TestBook::bookUrl,
                findBook = { null },
            )

        assertNull(result)
    }

    @Test
    fun `audio notifications carry book identity`() {
        val playService =
            projectFile("src/main/java/io/legado/app/service/AudioPlayService.kt").readText()
        val cacheService =
            projectFile("src/main/java/io/legado/app/service/AudioCacheService.kt").readText()

        assertTrue(playService.containsCode("putExtra(\"bookUrl\", it.bookUrl)"))
        assertFalse(playService.containsCode("putExtra(\"inBookshelf\""))
        assertTrue(cacheService.containsCode("putExtra(\"bookUrl\", bookUrl)"))
        assertTrue(cacheService.containsCode("notificationBuilder.setContentIntent(contentIntent)"))
        assertTrue(cacheService.containsCode("currentBookUrl.takeIf { it.isNotBlank() }"))
    }

    @Test
    fun `audio activity consumes notification updates`() {
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt").readText()
        val onNewIntent =
            activity
                .substringAfter("override fun onNewIntent(intent: Intent)")
                .substringBefore("private fun menuAction")
        val beforeInit = onNewIntent.substringBefore("viewModel.initialize(")

        assertTrue(onNewIntent.containsCode("setIntent(intent)"))
        assertTrue(beforeInit.containsCode("shouldReuseCurrentAudioPlay("))
        assertTrue(onNewIntent.containsCode("viewModel.initialize("))
        assertTrue(
            onNewIntent.containsCode("viewModel.initialize(requestedBookUrl, freshRequest = true)")
        )
    }

    @Test
    fun `audio initialization is serialized and refreshes shelf state`() {
        val viewModel =
            projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayRepository.kt")
                .readText()

        assertTrue(viewModel.containsCode("private val engineWrites = Mutex()"))
        assertTrue(
            projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt")
                .readText()
                .containsCode("initTask?.cancel()")
        )
        assertTrue(viewModel.containsCode("engineWrites.withLock"))
        assertTrue(viewModel.containsCode("val cachedBook = AudioPlay.book"))
        assertFalse(viewModel.containsCode("cachedBook.takeUnless"))
        assertFalse(viewModel.containsCode("getBooleanExtra(\"inBookshelf\""))
        assertTrue(viewModel.containsCode("val resolvedBook = resolveAudioPlayBook("))
        assertFalse(viewModel.containsCode("cachedChapterIndex"))
        assertFalse(viewModel.containsCode("cachedChapterPos"))
        assertTrue(viewModel.containsCode("val temporaryBook = targetBook.copy().apply"))
        assertTrue(viewModel.containsCode("appDb.bookDao.insertIgnore(temporaryBook)"))
        assertTrue(viewModel.containsCode("databaseBook = appDb.bookDao.getBook(requestedBookUrl)"))
        assertTrue(viewModel.containsCode("targetBook = checkNotNull(databaseBook)"))
        assertTrue(viewModel.containsCode("else !(databaseBook ?: targetBook).isNotShelf"))

        val audioPlay = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt").readText()
        val upData =
            audioPlay
                .substringAfter("fun upData(book: Book, preserveProgress: Boolean)")
                .substringBefore("fun resetData(book: Book)")
        assertTrue(upData.containsCode("val playbackChanged = synchronized(this)"))
        assertTrue(upData.containsCode("if (preserveProgress &&"))
        assertTrue(upData.containsCode("book.durChapterIndex = durChapterIndex"))
        assertTrue(upData.containsCode("book.durChapterPos = durChapterPos"))
        assertTrue(upData.containsCode("AudioPlay.book = book"))
        assertTrue(viewModel.containsCode("AudioPlay.upData(book, preserveProgress = true)"))
        assertTrue(viewModel.containsCode("AudioPlay.upData(book, preserveProgress = false)"))
    }

    @Test
    fun `source change refreshes the running notification`() {
        val viewModel =
            projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayRepository.kt")
                .readText()
        val service =
            projectFile("src/main/java/io/legado/app/service/AudioPlayService.kt").readText()
        val updateAction =
            service
                .substringAfter("ACTION_UPDATE_NOTIFICATION ->")
                .substringBefore("IntentAction.stop ->")

        assertTrue(viewModel.containsCode("AudioPlayService.updateNotification(context)"))
        assertTrue(updateAction.containsCode("upMediaMetadata()"))
        assertTrue(updateAction.containsCode("upAudioPlayNotification()"))
        assertTrue(viewModel.containsCode("appDb.bookDao.getBook(it.bookUrl)?.isNotShelf ?: true"))
        assertTrue(viewModel.containsCode("if (wasNotShelf) book.addType(BookType.notShelf)"))
        assertTrue(viewModel.containsCode("AudioPlay.inBookshelf = !wasNotShelf"))
    }

    @Test
    fun `book loading failure is propagated`() {
        val viewModel =
            projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayRepository.kt")
                .readText()

        assertTrue(
            viewModel.containsCode("private suspend fun initBook(book: Book, owner: Long): Boolean")
        )
        assertTrue(
            viewModel.containsCode(
                "if (AudioPlay.chapterSize == 0 && workingBook.tocUrl.isEmpty())"
            )
        )
        assertTrue(
            viewModel.containsCode("loadChapterList(workingBook, source, owner, engineIdentity)")
        )
        assertTrue(viewModel.containsCode("if (chapters.isEmpty()) return false"))
        assertTrue(viewModel.containsCode("return false"))
    }

    private fun String.containsCode(expected: String): Boolean =
        filterNot(Char::isWhitespace).contains(expected.filterNot(Char::isWhitespace))

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }

    private data class TestBook(val bookUrl: String)
}
