package io.legado.app.ui.menu

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.ReaderMenuSettingsRepository
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.ui.book.read.config.ReaderMenuConfigViewModel
import io.legado.app.ui.book.read.config.ReaderMenuEditAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMenuConfigContractTest {

    @Test
    fun readerOverflowUsesConfigAndKeepsHiddenActionsReachable() {
        val source = read("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        listOf(
                "override fun onShowActivityOverflowMenu(anchor: View, menu: Menu): Boolean",
                "loadReaderMenuConfig(this)",
                "showReaderOverflowMenu(anchor, menu)",
                "showReaderMoreMenu(anchor, menu)",
                "readerOverflowItemsByKey()",
                "ACTION_READER_MORE",
                "ACTION_READER_CONFIG",
                "menu_reader_all_features)?.isVisible = true",
                "refreshReaderMenu()",
                "invalidateOptionsMenu()",
            )
            .forEach { expected -> assertTrue("missing $expected", source.contains(expected)) }
        assertFalse(source.contains("androidx.appcompat.widget.PopupMenu"))
    }

    @Test
    fun readerConfigPreservesReimportInMoreAndPersistsReorderAndBulkPayloads() {
        var persisted: ReaderMenuConfig? = null
        val model =
            ReaderMenuConfigViewModel(
                object : ReaderMenuSettingsRepository {
                    override fun load() = ReaderMenuConfig.default()

                    override fun save(config: ReaderMenuConfig) {
                        persisted = config
                    }
                },
                SavedStateHandle(),
            )
        model.edit(ReaderMenuEditAction.Toggle("reimportSource", false))
        assertFalse("reimportSource" in persisted!!.primary)
        assertEquals(listOf("reimportSource"), persisted!!.more)
        model.edit(ReaderMenuEditAction.StartReorder("bookmark"))
        model.edit(ReaderMenuEditAction.Move("bookmark", "highlightRule"))
        model.edit(ReaderMenuEditAction.FinishGesture(true))
        assertEquals(listOf("highlightRule", "bookmark"), persisted!!.primary.take(2))
        model.edit(ReaderMenuEditAction.StartSelection("highlightRule"))
        model.edit(ReaderMenuEditAction.SelectionTo("bookmark"))
        model.edit(ReaderMenuEditAction.FinishGesture(true))
        assertEquals(listOf("highlightRule", "bookmark", "reimportSource"), persisted!!.more)
        assertEquals(
            ReaderMenuConfig.ALL_KEYS.toSet(),
            (persisted!!.primary + persisted!!.more).toSet(),
        )
        model.edit(ReaderMenuEditAction.SetAll(false))
        assertTrue(persisted!!.primary.isEmpty())
        assertEquals(ReaderMenuConfig.ALL_KEYS.size, persisted!!.more.size)
        model.edit(ReaderMenuEditAction.Reset)
        assertEquals(ReaderMenuConfig.default(), persisted)
    }

    @Test
    fun menuAndPreferenceExposeConfigurationEntry() {
        val menu = read("src/main/res/menu/book_read.xml")
        val preference = read("src/main/res/xml/pref_config_read.xml")
        val backupConfig = read("src/main/java/io/legado/app/help/storage/BackupConfig.kt")
        assertTrue(menu.contains("@+id/menu_reader_more"))
        assertTrue(menu.contains("@+id/menu_reader_all_features"))
        assertTrue(preference.contains("android:key=\"customReaderMenu\""))
        assertTrue(backupConfig.contains("PreferKey.readerMenuConfig"))
    }

    private fun read(path: String): String {
        return sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()
    }
}
