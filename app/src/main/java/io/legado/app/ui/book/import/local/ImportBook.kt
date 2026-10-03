package io.legado.app.ui.book.import.local

class ImportBookShelfFiles(
    fileNames: Iterable<String>,
    alternateOrigins: Iterable<String>,
) {
    private val fileNames = fileNames.toHashSet()
    private val alternateOrigins = alternateOrigins.toHashSet()

    operator fun contains(fileName: String): Boolean {
        return fileName in fileNames ||
            alternateOrigins.any {
                it.endsWith(fileName, ignoreCase = true)
            }
    }
}
