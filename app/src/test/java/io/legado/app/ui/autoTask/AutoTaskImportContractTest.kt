package io.legado.app.ui.autoTask

import io.legado.app.ui.association.jsonImportType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskImportContractTest {

    @Test
    fun `automatic task import is routed from files and links`() {
        val base =
            projectFile("src/main/java/io/legado/app/ui/association/BaseAssociationViewModel.kt")
        val online =
            projectFile("src/main/java/io/legado/app/ui/association/OnLineImportActivity.kt")
        val file =
            projectFile("src/main/java/io/legado/app/ui/association/FileAssociationActivity.kt")

        assertEquals("autoTask", jsonImportType(mapOf("cron" to "0 * * * *", "script" to "test")))
        assertEquals(null, jsonImportType(mapOf("cron" to "0 * * * *")))
        assertEquals(null, jsonImportType(mapOf("script" to "test")))
        assertTrue(base.contains("successLive.postValue(type to uri.toString())"))
        assertTrue(online.contains("\"/autoTask\" -> showDialogFragment("))
        assertTrue(online.contains("\"/auto\" -> viewModel.determineType("))
        assertTrue(online.contains("\"autoTask\" -> showDialogFragment("))
        assertTrue(file.contains("\"autoTask\" -> showDialogFragment("))
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
