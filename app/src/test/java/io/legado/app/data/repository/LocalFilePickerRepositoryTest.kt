package io.legado.app.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class LocalFilePickerRepositoryTest {
    private lateinit var root: File
    private val repository = DiskLocalFilePickerRepository()

    @Before
    fun setup() {
        root = Files.createTempDirectory("compose-file-picker").toFile()
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun realDirectorySortingBreadcrumbAndExtensionRowsPreserveExactOriginalSemantics() =
        runBlocking {
            File(root, "B.txt").writeText("Text")
            File(root, "A.TXT").writeText("Upper")
            File(root, ".hidden").mkdir()
            val dir = File(root, "Z directory").apply { mkdir() }
            val config = LocalFilePickerConfig(root.path, extensions = listOf("txt"))
            val loaded = repository.list(config, root.path)
            assertNull(loaded.parent)
            assertTrue(loaded.crumbs.isEmpty())
            assertEquals(
                listOf(".hidden", "Z directory", "A.TXT", "B.txt"),
                loaded.rows.map { it.name },
            )
            assertEquals(listOf(true, true, false, true), loaded.rows.map { it.enabled })
            val nested = repository.list(config, dir.path)
            assertEquals(root.canonicalPath, nested.parent)
            assertEquals(listOf(LocalFilePickerCrumb(dir.canonicalPath, dir.name)), nested.crumbs)
            assertTrue(
                repository.list(config.copy(showHidden = true), root.path).rows.any {
                    it.name == ".hidden"
                }
            )
        }

    @Test
    fun directoriesRemainBrowsableAndFilesDisabledForDirectoryMode() = runBlocking {
        val file = File(root, "file").apply { writeText("Data") }
        val dir = File(root, "sub").apply { mkdir() }
        val config = LocalFilePickerConfig(root.path, selectDirectory = true)
        val loaded = repository.list(config, root.path)
        assertTrue(loaded.rows.first { it.path == dir.canonicalPath }.enabled)
        assertFalse(loaded.rows.first { it.path == file.canonicalPath }.enabled)
        assertEquals(root.canonicalPath, repository.validate(config, root.path))
        assertTrue(runCatching { repository.validate(config, file.path) }.isFailure)
    }

    @Test
    fun actualFolderCreationIsTrimmedAndCannotEscapeCurrentDirectoryOrRoot() = runBlocking {
        val config = LocalFilePickerConfig(root.path)
        val directory = File(root, "current").apply { mkdir() }
        repository.create(config, directory.path, " New folder ")
        assertTrue(File(directory, "New folder").isDirectory)
        listOf("", " ", ".", "../outside", "../../outside", directory.absolutePath).forEach { name
            ->
            assertTrue(
                "$name must fail",
                runCatching { repository.create(config, directory.path, name) }.isFailure,
            )
        }
        assertFalse(File(root, "outside").exists())
        assertTrue(
            runCatching { repository.create(config, directory.path, "New folder") }.isFailure
        )
    }

    @Test
    fun symbolicLinksCannotBrowseCreateOrSelectOutsideFixedRoot() = runBlocking {
        val outside = Files.createTempDirectory("compose-file-outside").toFile()
        try {
            val other = File(outside, "file.txt").apply { writeText("Other") }
            val link = File(root, "link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val config = LocalFilePickerConfig(root.path)
            assertFalse(repository.list(config, root.path).rows.single().enabled)
            assertTrue(runCatching { repository.list(config, link.path) }.isFailure)
            assertTrue(runCatching { repository.create(config, root.path, "link/new") }.isFailure)
            assertTrue(
                runCatching { repository.validate(config, File(link, other.name).path) }.isFailure
            )
            assertFalse(File(outside, "new").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun confirmedFileIsRevalidatedAfterDeletionAndLiteralWildcardIsNotExpanded() = runBlocking {
        val file = File(root, "book.txt").apply { writeText("Text") }
        val config = LocalFilePickerConfig(root.path, extensions = listOf("txt"))
        assertEquals(file.canonicalPath, repository.validate(config, file.path))
        assertFalse(
            DiskLocalFilePickerRepository.allowed(config.copy(extensions = listOf("*")), file.path)
        )
        file.delete()
        assertTrue(runCatching { repository.validate(config, file.path) }.isFailure)
        assertTrue(runCatching { repository.list(config, file.path) }.isFailure)
    }

    @Test
    fun actualFileValidationReturnsTypedIssuesRatherThanEnglishDisplayMessages() = runBlocking {
        val config = LocalFilePickerConfig(root.path)
        suspend fun assertIssue(expected: LocalFilePickerIssue, action: suspend () -> Unit) {
            val failure = runCatching { action() }.exceptionOrNull()
            assertTrue(failure is LocalFilePickerIssueException)
            assertEquals(expected, (failure as LocalFilePickerIssueException).issue)
        }
        assertIssue(LocalFilePickerIssue.DirectoryMissing) {
            repository.list(config, File(root, "missing").path)
        }
        assertIssue(LocalFilePickerIssue.FolderNameRequired) {
            repository.create(config, root.path, " ")
        }
        assertIssue(LocalFilePickerIssue.InvalidFolderName) {
            repository.create(config, root.path, "../outside")
        }
        assertIssue(LocalFilePickerIssue.OutsideRoot) {
            repository.list(config, checkNotNull(root.parentFile).path)
        }
        repository.create(config, root.path, "existing")
        assertIssue(LocalFilePickerIssue.CreateFailed) {
            repository.create(config, root.path, "existing")
        }
        assertIssue(LocalFilePickerIssue.SelectionInvalid) {
            repository.validate(config, File(root, "missing.txt").path)
        }
    }
}
