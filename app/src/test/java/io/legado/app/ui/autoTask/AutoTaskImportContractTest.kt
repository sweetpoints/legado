package io.legado.app.ui.autoTask

import io.legado.app.data.association.associationOnlineRoute
import io.legado.app.ui.association.jsonImportType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskImportContractTest {

    @Test
    fun `automatic task import is routed from files and links`() {
        assertEquals("autoTask", jsonImportType(mapOf("cron" to "0 * * * *", "script" to "test")))
        assertEquals(null, jsonImportType(mapOf("cron" to "0 * * * *")))
        assertEquals(null, jsonImportType(mapOf("script" to "test")))
        assertEquals("autoTask", associationOnlineRoute("/autoTask", "import").importType)
        assertEquals(null, associationOnlineRoute("/auto", "import").importType)
        // Untyped auto links must determine the actual downloaded JSON format.
        assertEquals("autoTask", jsonImportType(mapOf("cron" to "daily", "script" to "run")))
    }

    @Test
    fun `automatic task share passphrase routes import and export`() {
        val main = projectFile("src/main/java/io/legado/app/ui/main/MainActivity.kt")

        assertTrue(main.contains("SourceSharePassphrase.Type.AUTO_TASK ->"))
        assertTrue(main.contains("showDialogFragment(ImportAutoTaskDialog(value.url))"))
    }

    @Test
    fun `automatic task import batches storage and scheduler refresh`() {
        val model = projectFile("src/main/java/io/legado/app/model/AutoTask.kt")
        val block = model.substringAfter("fun importRules(").substringBefore("fun delete(")

        assertTrue(block.contains("if (rules.isEmpty()) return emptyList()"))
        assertTrue(block.contains("prepareImportedAutoTasks(all(), rules)"))
        assertTrue(block.contains("appDb.autoTaskRuleDao.upsert(*imported.toTypedArray())"))
        assertTrue(block.contains("AutoTaskScheduler.refresh(context)"))
    }

    private fun projectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInApp")
    }
}
