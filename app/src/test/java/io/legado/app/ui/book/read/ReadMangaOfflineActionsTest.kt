package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadMangaOfflineActionsTest {

    @Test
    fun `manga reader exposes offline cache and long press image saving`() {
        val activity =
            projectFile("src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt").readText()
        val viewModel =
            projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderComposeViewModel.kt")
                .readText()
        val menu =
            projectFile("src/main/java/io/legado/app/ui/book/manga/MangaMenuScreen.kt").readText()
        val preferKey = projectFile("src/main/java/io/legado/app/constant/PreferKey.kt").readText()
        val appConfig =
            projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        val downloadDialog =
            projectFile("src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt")
                .readText()
        val cacheBook = projectFile("src/main/java/io/legado/app/model/CacheBook.kt").readText()
        val imageRepository =
            projectFile("src/main/java/io/legado/app/data/image/MangaImageRepository.kt").readText()
        val operations =
            projectFile(
                    "src/main/java/io/legado/app/data/repository/MangaReaderOperationsRepository.kt"
                )
                .readText()
        val route =
            projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderRoute.kt").readText()
        val bookCover = projectFile("src/main/java/io/legado/app/model/BookCover.kt").readText()

        assertTrue(menu.contains("MangaMenuAction.Download"))
        assertTrue(menu.contains("MangaReaderSetting.LongPressSave"))
        assertTrue(activity.contains("showBookDownloadDialog(it)"))
        assertTrue(route.contains("onLongPress = { viewModel.saveImage(it.imageUrl) }"))
        assertTrue(downloadDialog.contains("fun Context.showBookDownloadDialog(book: Book)"))
        assertTrue(viewModel.contains("MangaImageSaveRequest("))
        assertTrue(operations.contains("BookHelp.saveImage("))
        assertTrue(
            operations.contains(".createFileIfNotExist(image.name)") &&
                operations.contains(".writeFile(image)")
        )
        val cacheSuccess =
            cacheBook
                .substringAfter("val ticket = downloads.claimManual(index)")
                // Ordinary offline caching still finishes only after its images are saved.
                // Theme resource refresh now has a separate staged transaction above this branch.
                .substringAfter("} else {")
                .substringBefore("private fun startManual")
        assertTrue(cacheSuccess.contains("val content = BookHelp.getContent(requestBook, chapter)"))
        assertTrue(
            cacheSuccess.contains("BookHelp.saveImages(source, requestBook, chapter, content, 1)")
        )
        assertTrue(
            cacheSuccess.contains(
                "val currentContent = BookHelp.getContent(requestBook, chapter) ?: content"
            )
        )
        assertTrue(
            cacheSuccess.indexOf("BookHelp.saveImages") < cacheSuccess.indexOf("onSuccess(ticket")
        )
        assertTrue(imageRepository.contains("BookHelp.getImage(book, request.imageUrl)"))
        assertTrue(
            imageRepository.contains("takeIf { it.isFile }?.absolutePath") &&
                imageRepository.contains("?: request.imageUrl")
        )
        assertTrue(bookCover.contains("ImageLoader.loadFile(context, path).apply(options)"))
        assertTrue(preferKey.contains("mangaLongClickSaveImage = \"mangaLongClickSaveImage\""))
        assertTrue(appConfig.contains("getPrefBoolean(PreferKey.mangaLongClickSaveImage, true)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
