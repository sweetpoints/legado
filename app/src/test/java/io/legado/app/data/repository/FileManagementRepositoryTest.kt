package io.legado.app.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class FileManagementRepositoryTest {
    private lateinit var directory: File

    @Before
    fun before() {
        directory = Files.createTempDirectory("legado-file-manager").toFile()
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    private fun repo() = LocalFileManagementRepository { directory }

    @Test
    fun realDirectoryListsFoldersBeforeFilesThenExactCaseSensitiveNameAndIncludesHiddenFiles() =
        runBlocking {
            File(directory, "z-folder").mkdir()
            File(directory, "a-folder").mkdir()
            File(directory, "Z.txt").writeText("Z")
            File(directory, "a.txt").writeText("a")
            File(directory, ".hidden").writeText("hidden")
            val value = repo().list(null)
            assertEquals(
                listOf("a-folder", "z-folder", ".hidden", "Z.txt", "a.txt"),
                value.entries.map { it.name },
            )
            assertEquals(listOf("root"), value.crumbs.map { it.name })
            assertTrue(value.entries.none { it.kind == ManagedFileKind.Parent })
        }

    @Test
    fun nestedDirectoryIncludesExplicitParentAndCompleteCrumbChainAndRootNeverIncludesParent() =
        runBlocking {
            val nested = File(directory, "one/two").apply { mkdirs() }
            File(nested, "file.txt").writeText("body")
            val value = repo().list(nested.path)
            assertEquals(listOf("root", "one", "two"), value.crumbs.map { it.name })
            assertEquals(File(directory, "one").canonicalPath, value.entries.first().path)
            assertEquals(ManagedFileKind.Parent, value.entries.first().kind)
            assertEquals("..", value.entries.first().name)
            assertEquals(directory.canonicalPath, repo().list(value.crumbs.first().path).directory)
        }

    @Test
    fun deleteIsNonrecursiveAndLeavesNonemptyFoldersAndUnrelatedFilesUntouched() = runBlocking {
        val empty = File(directory, "empty").apply { mkdir() }
        val nonempty = File(directory, "nonempty").apply { mkdir() }
        val child = File(nonempty, "child").apply { writeText("kept") }
        val file = File(directory, "file.txt").apply { writeText("body") }
        assertFalse(repo().delete(nonempty.path))
        assertEquals("kept", child.readText())
        assertTrue(repo().delete(empty.path))
        assertFalse(empty.exists())
        assertTrue(repo().delete(file.path))
        assertFalse(file.exists())
        assertTrue(child.exists())
    }

    @Test
    fun rootAndOutsidePathsCannotBeDeletedOrOpenedThroughManagedDirectory() = runBlocking {
        val outside = Files.createTempDirectory("legado-file-outside").toFile()
        val file = File(outside, "kept").apply { writeText("kept") }
        try {
            assertTrue(runCatching { repo().delete(directory.path) }.isFailure)
            assertTrue(runCatching { repo().delete(file.path) }.isFailure)
            assertTrue(runCatching { repo().open(file.path) }.isFailure)
            assertEquals("kept", file.readText())
            assertTrue(directory.exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun ordinaryFileOpenPreservesExactUriForSpacesAndRejectsDirectoryAndDisappearedFile() =
        runBlocking {
            val file = File(directory, "Chinese 中文 space.txt").apply { writeText("body") }
            assertEquals(file.toURI().toString(), repo().open(file.path))
            assertTrue(runCatching { repo().open(directory.path) }.isFailure)
            file.delete()
            assertTrue(runCatching { repo().open(file.path) }.isFailure)
        }

    @Test
    fun unavailableExternalRootIsEmptyAndMissingDirectoryFailsWithoutCreatingFiles() = runBlocking {
        val unavailable = LocalFileManagementRepository { null }.list(null)
        assertNull(unavailable.directory)
        assertTrue(unavailable.entries.isEmpty())
        val missing = File(directory, "missing")
        assertTrue(runCatching { repo().list(missing.path) }.isFailure)
        assertFalse(missing.exists())
    }

    @Test
    fun snapshotsRemainImmutableAfterFilesAreRenamedOrDeleted() = runBlocking {
        val file = File(directory, "original").apply { writeText("body") }
        val old = repo().list(null)
        file.renameTo(File(directory, "changed"))
        val fresh = repo().list(null)
        assertEquals("original", old.entries.single().name)
        assertEquals("changed", fresh.entries.single().name)
    }

    @Test
    fun symbolicLinkOutsideManagedRootCannotEscapeDuringNavigation() = runBlocking {
        val outside = Files.createTempDirectory("legado-file-outside").toFile()
        try {
            val link = File(directory, "link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(runCatching { repo().list(link.path) }.isFailure)
            assertTrue(outside.exists())
        } finally {
            File(directory, "link").delete()
            outside.deleteRecursively()
        }
    }
}
