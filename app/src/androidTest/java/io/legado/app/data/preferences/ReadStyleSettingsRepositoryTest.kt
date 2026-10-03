package io.legado.app.data.preferences

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.book.ResourceThemeGeneration
import io.legado.app.help.config.ReadBookConfig
import org.junit.Assert.*
import org.junit.Test

class ReadStyleSettingsRepositoryTest {
    @Test
    fun restorationKeepsBothPresetIndicesAndInvalidatesResourceGenerationWithoutChangingComicContext() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val repository = AppReadStyleSettingsRepository()
            val before = repository.checkpoint()
            val comicBefore = ReadBookConfig.isComic
            try {
                ReadBookConfig.configList.clear()
                ReadBookConfig.configList.addAll(
                    (0..4).map { ReadBookConfig.Config().copy(name = "Saved $it") }
                )
                ReadBookConfig.readStyleSelect = 1
                ReadBookConfig.comicStyleSelect = 3
                ReadBookConfig.isComic = true
                val checkpoint = repository.checkpoint()
                ReadBookConfig.configList.clear()
                ReadBookConfig.configList.addAll(
                    (0..4).map { ReadBookConfig.Config().copy(name = "New $it") }
                )
                ReadBookConfig.isComic = false
                val generation = ResourceThemeGeneration.current()
                repository.restore(checkpoint)
                assertFalse(ReadBookConfig.isComic)
                assertEquals(1, ReadBookConfig.readStyleSelect)
                assertEquals(3, ReadBookConfig.comicStyleSelect)
                assertEquals("Saved 1", ReadBookConfig.durConfig.name)
                assertTrue(ResourceThemeGeneration.current() > generation)
                ReadBookConfig.isComic = true
                assertEquals("Saved 3", ReadBookConfig.durConfig.name)
            } finally {
                repository.restore(before)
                ReadBookConfig.isComic = comicBefore
            }
        }
    }
}
