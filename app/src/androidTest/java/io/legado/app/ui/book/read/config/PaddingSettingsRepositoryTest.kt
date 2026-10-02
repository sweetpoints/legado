package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.preferences.AppPaddingSettingsRepository
import io.legado.app.data.preferences.PaddingRegion
import io.legado.app.data.preferences.PaddingSide
import io.legado.app.help.config.ReadBookConfig
import org.junit.Assert.*
import org.junit.Test

class PaddingSettingsRepositoryTest {
    @Test fun paddingWritesFollowActiveLocalOrSharedStyleAndResetUsesConfigDefaults() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val configs = ReadBookConfig.configList.map { it.copy() }
            val shared = ReadBookConfig.shareConfig.copy()
            val selectedStyle = ReadBookConfig.readStyleSelect
            val shareLayout = ReadBookConfig.shareLayout
            try {
                ReadBookConfig.shareLayout = false
                ReadBookConfig.readStyleSelect = 0
                ReadBookConfig.configList[0] = ReadBookConfig.Config(paddingLeft = 10, paddingRight = 20, headerPaddingTop = 11)
                ReadBookConfig.shareConfig = ReadBookConfig.Config(paddingLeft = 30, paddingRight = 40, headerPaddingTop = 33, showHeaderLine = true)
                val repository = AppPaddingSettingsRepository()
                repository.apply(PaddingRegion.BODY, PaddingSide.LEFT, 90, true)
                assertEquals(90, ReadBookConfig.configList[0].paddingLeft)
                assertEquals(90, ReadBookConfig.configList[0].paddingRight)
                assertEquals(30, ReadBookConfig.shareConfig.paddingLeft)
                ReadBookConfig.shareLayout = true
                repository.apply(PaddingRegion.HEADER, PaddingSide.TOP, 44, false)
                assertEquals(44, ReadBookConfig.shareConfig.headerPaddingTop)
                assertEquals(11, ReadBookConfig.configList[0].headerPaddingTop)
                repository.reset(PaddingRegion.HEADER)
                val defaults = ReadBookConfig.Config()
                assertEquals(defaults.headerPaddingTop, ReadBookConfig.shareConfig.headerPaddingTop)
                assertEquals(defaults.headerPaddingLeft, ReadBookConfig.shareConfig.headerPaddingLeft)
                assertTrue(ReadBookConfig.shareConfig.showHeaderLine)
                assertEquals(11, ReadBookConfig.configList[0].headerPaddingTop)
            } finally {
                ReadBookConfig.configList.clear()
                ReadBookConfig.configList.addAll(configs)
                ReadBookConfig.shareConfig = shared
                ReadBookConfig.readStyleSelect = selectedStyle
                ReadBookConfig.shareLayout = shareLayout
            }
        }
    }
}
