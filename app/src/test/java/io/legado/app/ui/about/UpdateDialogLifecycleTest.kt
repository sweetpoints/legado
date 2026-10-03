package io.legado.app.ui.about

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogLifecycleTest {

    @Test
    fun `update callbacks skip dialogs after fragment state is saved`() {
        val main =
            functionBody(
                "src/main/java/io/legado/app/ui/main/MainActivity.kt",
                "private suspend fun upVersion()",
                "private suspend fun setLocalPassword()",
            )
        val shared =
            projectFile("src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt").readText()

        assertGuardBeforeDialog(main, "supportFragmentManager.isStateSaved")
        assertGuardBeforeDialog(shared, "isAdded && !childFragmentManager.isStateSaved")
        // About 页已迁移为 Compose：更新入口从 AboutFragment 移到 AboutActivity
        for (path in listOf("ui/about/AboutActivity.kt", "ui/main/my/MyNavigation.kt")) {
            val caller = projectFile("src/main/java/io/legado/app/$path").readText()
            assertTrue(caller.contains("\"check_update\" -> checkAppUpdate()"))
            assertTrue(caller.contains("\"check_beta_update\" -> checkAppUpdate(beta = true)"))
        }
    }

    @Test
    fun `manual update errors show their message without a redundant action prefix`() {
        val shared =
            projectFile("src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt").readText()
        assertTrue(shared.contains("AppUpdate.checkBeta(lifecycleScope)"))
        assertTrue(shared.contains("AppUpdate.gitHubUpdate.check(lifecycleScope)"))
        assertTrue(shared.contains("appCtx.toastOnUi(it.localizedMessage)"))
        assertFalse(shared.contains("getString(R.string.check_update)"))
        assertFalse(shared.contains("getString(R.string.check_beta_update)"))
    }

    private fun assertGuardBeforeDialog(source: String, guard: String) {
        assertTrue(source.indexOf(guard) in 0 until source.indexOf("UpdateDialog(it)"))
    }

    private fun functionBody(path: String, start: String, end: String): String {
        return projectFile(path).readText().substringAfter(start).substringBefore(end)
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
