package io.legado.app.data.repository

import android.graphics.Bitmap
import io.legado.app.help.BottomBarSkinManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BottomBarAssignmentRepositoryTest {
    private val repo = AppBottomBarAssignmentRepository()
    private val sessions = mutableListOf<String>()
    private val skins = mutableListOf<String>()
    private var previousActive = ""

    @Before
    fun setup() {
        previousActive = BottomBarSkinManager.active
    }

    @After
    fun cleanup() {
        sessions.forEach(BottomBarSkinManager::discardSession)
        skins.asReversed().forEach(BottomBarSkinManager::delete)
        BottomBarSkinManager.active = previousActive
    }

    private fun stage(vararg names: String): String {
        val bitmap =
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff2471af.toInt())
            }
        val bytes =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { stream ->
            names.forEach { name ->
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return BottomBarSkinManager.extractImages(zip.toByteArray().inputStream())
            .getOrThrow()
            .also { sessions += it }
    }

    @Test
    fun actualStagingProjectsSortedDecodableNamesPrefillAndSizedPreview() = runBlocking {
        val session = stage("z.png", "bookshelf_normal.png", "bookshelf_selected.png")
        val data = repo.load(session, 56)
        assertEquals(listOf("bookshelf_normal.png", "bookshelf_selected.png", "z.png"), data.images)
        assertEquals("bookshelf_selected.png", data.slots.first().selected)
        assertEquals("bookshelf_normal.png", data.slots.first().normal)
        assertEquals(4, data.slots.size)
        assertEquals(56, repo.preview(session, "z.png", 56)!!.width)
        assertNull(repo.preview(session, "../z.png", 56))
    }

    @Test
    fun oneSelectedSlotSavesAndManagerOwnsCurrentThemeAndSessionCleanup() = runBlocking {
        val session = stage("custom.png")
        val name = "Compose-${UUID.randomUUID()}"
        val rows =
            AppBottomBarAssignmentRepository.slots.map {
                BottomBarAssignmentSlot(it, if (it == "notes") "custom.png" else null)
            }
        val actual = repo.save(session, " $name ", null, rows)
        skins += actual
        assertEquals(name, actual)
        assertTrue(BottomBarSkinManager.hasSkin(actual))
        assertEquals(actual, BottomBarSkinManager.active)
        assertTrue(BottomBarSkinManager.stagingImages(session).isEmpty())
        assertEquals(1, BottomBarSkinManager.getPreviewBitmaps(actual, 40).size)
    }

    @Test
    fun editRenameMigratesActiveThemeAndInactiveEditDoesNotReplaceActiveTheme() = runBlocking {
        val firstSession = stage("home_selected.png")
        val initial =
            repo.save(
                firstSession,
                "Compose-${UUID.randomUUID()}",
                null,
                repo.load(firstSession, 56).slots,
            )
        skins += initial
        val editSession =
            BottomBarSkinManager.stageExisting(initial).getOrThrow().also { sessions += it }
        val renamed =
            repo.save(
                editSession,
                "Renamed-${UUID.randomUUID()}",
                initial,
                repo.load(editSession, 56).slots,
            )
        skins += renamed
        assertFalse(BottomBarSkinManager.hasSkin(initial))
        assertEquals(renamed, BottomBarSkinManager.active)
        BottomBarSkinManager.active = ""
        val inactiveSession =
            BottomBarSkinManager.stageExisting(renamed).getOrThrow().also { sessions += it }
        val inactive =
            repo.save(
                inactiveSession,
                "Inactive-${UUID.randomUUID()}",
                renamed,
                repo.load(inactiveSession, 56).slots,
            )
        skins += inactive
        assertEquals("", BottomBarSkinManager.active)
        assertFalse(BottomBarSkinManager.hasSkin(renamed))
    }

    @Test
    fun unknownFilenameCannotEscapeSessionAndDiscardDoesNotCreateSkin() = runBlocking {
        val session = stage("home_selected.png")
        val rows =
            repo.load(session, 56).slots.map {
                if (it.slot == "home") it.copy(selected = "../outside.png") else it
            }
        assertTrue(runCatching { repo.save(session, "Invalid", null, rows) }.isFailure)
        assertEquals(1, BottomBarSkinManager.stagingImages(session).size)
        repo.discard(session)
        repo.discard(session)
        assertTrue(BottomBarSkinManager.stagingImages(session).isEmpty())
    }
}
