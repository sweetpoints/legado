package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.BottomBarSkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BottomBarSkinCatalogRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = AppBottomBarSkinCatalogRepository(context)
    private val sessions = mutableListOf<String>(); private val skins = mutableListOf<String>()
    private val directory = File(context.cacheDir, "skin-catalog-${UUID.randomUUID()}")
    private var previousActive = ""
    @Before fun setup() { previousActive = BottomBarSkinManager.active; directory.mkdirs() }
    @After fun cleanup() {
        sessions.forEach(BottomBarSkinManager::discardSession); skins.asReversed().forEach(BottomBarSkinManager::delete)
        BottomBarSkinManager.active = previousActive; directory.deleteRecursively()
    }
    private fun zip(name: String, entry: String = "home_selected.png"): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffab3125.toInt()) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        return File(directory, name).also { file -> ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(entry)); zip.write(png); zip.closeEntry() } }
    }
    private suspend fun createSkin(): String {
        val staged = repo.importZip(zip("Fixture.zip").toURI().toString()); sessions += staged.session
        val assignments = AppBottomBarAssignmentRepository().load(staged.session, 56).slots
        return AppBottomBarAssignmentRepository().save(staged.session, "Catalog-${UUID.randomUUID()}", null, assignments).also { skins += it }
    }
    @Test fun realZipImportRetainsDisplayNameAndOnlyCreatesPrivateStaging() = runBlocking(Dispatchers.IO) {
        val before = BottomBarSkinManager.list(); val file = zip(" Display name .ZIP")
        val staged = repo.importZip(file.toURI().toString()); sessions += staged.session
        assertEquals(" Display name ", staged.name); assertNull(staged.editName)
        assertEquals(listOf("home_selected.png"), BottomBarSkinManager.stagingImages(staged.session).map { it.name })
        assertEquals(before, BottomBarSkinManager.list()); repo.discard(staged.session); assertTrue(BottomBarSkinManager.stagingImages(staged.session).isEmpty())
    }
    @Test fun loadSelectDefaultPreviewAndEditingPreserveExistingManagerContract() = runBlocking(Dispatchers.IO) {
        val name = createSkin(); val catalog = repo.load(); assertTrue(name in catalog.names); assertEquals(catalog.names.sorted(), catalog.names); assertEquals(name, catalog.active)
        val preview = repo.preview(name, 24); assertEquals(1, preview.size); assertEquals(24, preview.first().width)
        assertEquals("", repo.activate("")); assertEquals(name, repo.activate(name))
        val staged = repo.edit(name); sessions += staged.session; assertEquals(name, staged.name); assertEquals(name, staged.editName)
        assertTrue(BottomBarSkinManager.hasSkin(name)); assertTrue(BottomBarSkinManager.stagingImages(staged.session).isNotEmpty())
        repo.discard(staged.session); assertTrue(BottomBarSkinManager.hasSkin(name))
    }
    @Test fun exportAndShareCacheZipCanBeImportedAndDeletingOnlyFixtureResetsActive() = runBlocking(Dispatchers.IO) {
        val name = createSkin(); val path = repo.zip(name); val archive = File(path)
        try {
            assertTrue(archive.isFile); val roundtrip = repo.importZip(archive.toURI().toString()); sessions += roundtrip.session
            assertEquals(listOf("home_selected.png"), BottomBarSkinManager.stagingImages(roundtrip.session).map { it.name })
            repo.delete(name); assertFalse(BottomBarSkinManager.hasSkin(name)); assertEquals("", repo.load().active)
        } finally { archive.parentFile?.deleteRecursively() }
    }
    @Test fun invalidZipAndUnknownNamesNeverDeleteOtherSkinsOrChangeActiveTheme() = runBlocking(Dispatchers.IO) {
        val active = BottomBarSkinManager.active; val before = BottomBarSkinManager.list()
        val invalid = File(directory, "invalid.zip").apply { writeText("not a zip") }
        val error = runCatching { repo.importZip(invalid.toURI().toString()) }.exceptionOrNull()
        assertTrue(error is BottomBarSkinCatalogException)
        assertTrue(runCatching { repo.activate("Missing-${UUID.randomUUID()}") }.isFailure)
        assertTrue(runCatching { repo.delete("") }.isFailure)
        assertEquals(before, BottomBarSkinManager.list()); assertEquals(active, BottomBarSkinManager.active)
    }
}
