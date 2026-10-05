package io.legado.app.model.jsSource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceDispatchSentinelTest {

    @Test
    fun `all web book source execution routes use Dart`() {
        val webBook = readProjectFile(
            "app/src/main/java/io/legado/app/model/webBook/WebBook.kt"
        )
        val contracts = mapOf(
            "searchBookAwait" to "search",
            "exploreBookAwait" to "explore",
            "getBookInfoAwait" to "info",
            "getChapterListAwait" to "toc",
            "getContentAwait" to "content",
        )
        contracts.forEach { (methodName, operation) ->
            val method = suspendMethodBody(webBook, methodName)
            assertTrue("$methodName must execute Dart", method.contains("DartSourceEngine.execute("))
            assertTrue("$methodName must keep its stage", method.contains("\"$operation\""))
        }
        val oldRoutes = listOf(
            "DartSourceEngine.selected", "JsSourceBook.", "AnalyzeRule", "AnalyzeUrl",
            "BookList.analyze", "BookInfo.analyze", "BookContent.analyze", "BookChapterList.analyze",
        )
        for (oldRoute in oldRoutes) {
            assertTrue("Old source execution route remains: $oldRoute", !webBook.contains(oldRoute))
        }
        assertTrue(webBook.contains("BookChapterList.updateBookTocInfo"))
        assertTrue(webBook.contains("BookHelp.saveContent"))
        val hook = suspendMethodBody(webBook, "runPreUpdateJs")
        assertTrue(hook.contains("DartSourceEngine.evaluate("))
        assertTrue(hook.contains("\"book\" to before"))
        assertTrue(hook.contains("\"sourceInfo\" to sourceInfo"))
        val batch = suspendMethodBody(webBook, "getContentBatchAwait")
        assertTrue(batch.contains("return chapters"))
    }

    private fun suspendMethodBody(source: String, methodName: String): String {
        val start = source.indexOf("suspend fun $methodName(")
        check(start >= 0) { "Missing suspend method $methodName" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "Missing body for suspend method $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        error("Unclosed body for suspend method $methodName")
    }

    private fun readProjectFile(path: String): String {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        val file = File(repositoryRoot, path)
        require(file.isFile) { "Project file not found: $file" }
        return file.readText()
    }

}
