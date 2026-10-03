package io.legado.app.ui.book.import

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSelectionCountResetTest {

    @Test
    fun `successful imports reset immutable selection after their accepted receipt`() {
        val source =
            projectFile("src/main/java/io/legado/app/ui/book/import/local/LocalImportViewModel.kt")
                .readText()
        val action =
            source
                .substringAfter("private fun importFiles(")
                .substringBefore("fun deleteSelection()")
        assertTrue(action.contains("val result = repository.importFiles(selected, groupName)"))
        assertTrue(action.contains("ownership.publish(request)"))
        assertTrue(action.contains("selected = emptySet()"))
        assertTrue(
            action.indexOf("val result = repository.importFiles(selected, groupName)") <
                action.indexOf("selected = emptySet()")
        )
        assertTrue(action.contains("file.row.id in result.importedIds"))
        assertTrue(action.contains("publishRows()"))
        assertFalse(action.contains("adapter."))
    }

    // Remote success and failure selection reset is exercised by RemoteLibraryOperationsTest.
    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
