package io.legado.app.ui.font

import io.legado.app.data.file.installFontFile
import io.legado.app.data.repository.FontEntry
import io.legado.app.data.repository.mergeFontEntries
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FontSelectionStyleTest {

    @Test
    fun `font import installs valid files without overwriting name conflicts`() {
        val root = Files.createTempDirectory("font-import").toFile()
        try {
            val fonts = root.resolve("font")
            val firstBytes = "font-one".encodeToByteArray()
            val first = installFontFile(
                ByteArrayInputStream(firstBytes),
                "folder\\Demo.ttf",
                fonts,
            ) { it.readBytes().contentEquals(firstBytes) }
            val duplicate = installFontFile(
                ByteArrayInputStream(firstBytes),
                "Demo.ttf",
                fonts,
            ) { true }
            val secondBytes = "font-two".encodeToByteArray()
            val second = installFontFile(
                ByteArrayInputStream(secondBytes),
                "Demo.ttf",
                fonts,
            ) { true }

            assertEquals("Demo.ttf", first.name)
            assertEquals(first, duplicate)
            assertEquals("Demo (1).ttf", second.name)
            assertArrayEquals(firstBytes, first.readBytes())
            assertArrayEquals(secondBytes, second.readBytes())
            assertTrue(fonts.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `font import rejects unsupported and invalid files without residue`() {
        val root = Files.createTempDirectory("font-import-invalid").toFile()
        try {
            val fonts = root.resolve("font")
            assertThrows(IllegalArgumentException::class.java) {
                installFontFile(ByteArrayInputStream(byteArrayOf(1)), "font.txt", fonts) { true }
            }
            assertThrows(IllegalArgumentException::class.java) {
                installFontFile(ByteArrayInputStream(byteArrayOf(1)), "font.otf", fonts) { false }
            }

            assertTrue(fonts.listFiles()?.isEmpty() == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent font imports never overwrite the same target`() {
        val root = Files.createTempDirectory("font-import-concurrent").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val fonts = root.resolve("font")
            val start = CountDownLatch(1)
            val contents = listOf("first-font", "second-font")
            val futures = contents.map { content ->
                executor.submit<File> {
                    start.await()
                    installFontFile(
                        ByteArrayInputStream(content.encodeToByteArray()),
                        "Concurrent.ttf",
                        fonts,
                    ) { true }
                }
            }
            start.countDown()
            val installed = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(
                setOf("Concurrent.ttf", "Concurrent (1).ttf"),
                installed.mapTo(hashSetOf(), File::getName),
            )
            assertEquals(contents.toSet(), installed.mapTo(hashSetOf(), File::readText))
            assertTrue(fonts.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun mergingKeepsSameNamesAtDifferentPathsAndPrefersExternalForDuplicatePath() {
        val external = listOf(FontEntry("/external/Demo.ttf", "file:///external/Demo.ttf", "Demo.ttf", false),
            FontEntry("/shared/Same.ttf", "file:///shared/Same.ttf", "Same.ttf", false))
        val local = listOf(FontEntry("/private/Demo.ttf", "file:///private/Demo.ttf", "Demo.ttf", true),
            FontEntry("/shared/Same.ttf", "file:///shared/Same.ttf", "Same.ttf", true))
        val merged = mergeFontEntries(external, local)
        assertEquals(3, merged.size)
        assertEquals(listOf("/external/Demo.ttf", "/private/Demo.ttf", "/shared/Same.ttf"), merged.map { it.path })
        assertFalse(merged.last().privateFolder)
    }

    @Test fun mergedFontsAreNameSortedAndPrivateFontsWorkWithoutAnExternalFolder() {
        val local = listOf(FontEntry("/private/Z.ttf", "", "Z.ttf", true), FontEntry("/private/A.otf", "", "A.otf", true))
        assertEquals(listOf("A.otf", "Z.ttf"), mergeFontEntries(emptyList(), local).map { it.name })
        assertTrue(mergeFontEntries(emptyList(), local).all { it.privateFolder })
    }
}
