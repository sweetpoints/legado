package io.legado.app.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ThemeImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun theme(name: String) =
        ThemeConfig.Config(name, false, "#112233", "#223344", "#334455", "#445566", false, null, 0)

    private suspend fun withThemes(block: suspend () -> Unit) =
        withContext(Dispatchers.IO) {
            val original = ThemeConfig.configList.map { it.copy() }
            val file = File(ThemeConfig.configFilePath)
            val bytes = if (file.exists()) file.readBytes() else null
            try {
                block()
            } finally {
                ThemeConfig.configList.clear()
                ThemeConfig.configList.addAll(original)
                if (bytes == null) file.delete() else file.writeBytes(bytes)
            }
        }

    @Test
    fun everyConfigurationFieldAffectsStatusWhileThemeNameSelectsExistingRecord() = runBlocking {
        withThemes {
            val stored = theme("Stored-${UUID.randomUUID()}")
            ThemeConfig.configList.add(stored)
            val variations =
                listOf(
                    stored.copy(),
                    stored.copy(themeName = "Fresh-${UUID.randomUUID()}"),
                    stored.copy(isNightTheme = true),
                    stored.copy(primaryColor = "#ffffff"),
                    stored.copy(accentColor = "#ffffff"),
                    stored.copy(backgroundColor = "#ffffff"),
                    stored.copy(bottomBackground = "#ffffff"),
                    stored.copy(transparentNavBar = true),
                    stored.copy(backgroundImgPath = "/new/image.png"),
                    stored.copy(backgroundImgBlur = 23),
                )
            val repo = AppThemeImportRepository(context)
            val items = repo.read(GSON.toJson(variations))
            assertEquals(ThemeImportStatus.Existing, items.first().status)
            assertFalse(items.first().selectedByDefault)
            assertEquals(ThemeImportStatus.New, items[1].status)
            assertTrue(
                items.drop(2).all { it.status == ThemeImportStatus.Update && it.selectedByDefault }
            )
            assertEquals(variations.map { GSON.toJson(it) }, items.map { it.json })
            val renamed = repo.edit(items.first().key, GSON.toJson(variations[1]))
            assertEquals(items.first().key, renamed.key)
            assertEquals(ThemeImportStatus.New, renamed.status)
            assertFalse(ThemeConfig.configList.any { it.themeName == variations[1].themeName })
        }
    }

    @Test
    fun selectedImportWritesThemeFileWithoutApplyingCurrentThemeAndInvalidColorsKeepOriginalSkipPolicy() =
        runBlocking {
            withThemes {
                val session = UUID.randomUUID().toString()
                val stored = theme("Stored-${UUID.randomUUID()}")
                val fresh = theme("Fresh-${UUID.randomUUID()}")
                val update = stored.copy(isNightTheme = true, backgroundImgBlur = 37)
                ThemeConfig.configList.add(stored)
                val prefsBefore = HashMap(context.defaultSharedPreferences.all)
                val repo = AppThemeImportRepository(context)
                try {
                    val items = repo.read(GSON.toJson(listOf(fresh, update, stored)))
                    repo.stage(session, items)
                    assertEquals(ThemeImportSession(items), repo.restore(session))
                    repo.insert(session, items, setOf(items[0].key, items[1].key))
                    assertEquals(
                        fresh,
                        ThemeConfig.configList.single { it.themeName == fresh.themeName },
                    )
                    assertEquals(
                        update,
                        ThemeConfig.configList.single { it.themeName == stored.themeName },
                    )
                    val saved =
                        GSON.fromJsonArray<ThemeConfig.Config>(
                                File(ThemeConfig.configFilePath).readText()
                            )
                            .getOrThrow()
                    assertEquals(update, saved.single { it.themeName == stored.themeName })
                    assertTrue(repo.restore(session)!!.committed)
                    assertEquals(prefsBefore, context.defaultSharedPreferences.all)
                    val invalid =
                        repo.edit(items[1].key, GSON.toJson(update.copy(primaryColor = "invalid")))
                    repo.insert(session, listOf(invalid), setOf(invalid.key))
                    assertEquals(
                        update,
                        ThemeConfig.configList.single { it.themeName == stored.themeName },
                    )
                } finally {
                    File(context.cacheDir, "theme-import/$session.json").delete()
                }
            }
        }

    @Test
    fun uriAndCodePayloadPreserveAllFieldsAndDoNotPersistBeforeConfirm() = runBlocking {
        withThemes {
            val value =
                theme("URI-${UUID.randomUUID()}")
                    .copy(
                        isNightTheme = true,
                        transparentNavBar = true,
                        backgroundImgPath = "https://example.invalid/image.png",
                        backgroundImgBlur = 49,
                    )
            val source = File(context.cacheDir, "theme-source-${UUID.randomUUID()}.json")
            val repo = AppThemeImportRepository(context)
            try {
                source.writeText(GSON.toJson(value))
                val items = repo.read(android.net.Uri.fromFile(source).toString())
                assertEquals(repo.read(GSON.toJson(value)), items)
                assertEquals(items.single(), repo.edit(items.single().key, items.single().json))
                assertTrue(runCatching { repo.edit("stable", "invalid") }.isFailure)
                assertFalse(ThemeConfig.configList.any { it.themeName == value.themeName })
            } finally {
                source.delete()
            }
        }
    }
}
