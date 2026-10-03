package io.legado.app.ui.code

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditorSelectionContractTest {

    @Test
    fun `overflow select all uses the editor document instead of ime extracted text`() {
        val screen =
            projectFile("app/src/main/java/io/legado/app/ui/code/CodeEditorScreen.kt").readText()
        val route =
            projectFile("app/src/main/java/io/legado/app/ui/code/CodeEditorRoute.kt").readText()
        val engine =
            projectFile("app/src/main/java/io/legado/app/ui/code/SoraCodeEditorEngine.kt")
                .readText()
        assertTrue(screen.contains("R.string.select_all"))
        assertTrue(screen.contains("CodeEditorAction.SELECT_ALL"))
        assertTrue(route.contains("CodeEditorAction.SELECT_ALL -> sora?.selectAll()"))
        assertTrue(engine.contains("view.selectAll()"))
        assertTrue(engine.contains("props.maxIPCTextLength = 64 * 1024"))
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }
}
