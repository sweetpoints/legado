package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Observer
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.*
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.eventObservable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadStyleSettingsRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val repository = AppReadStyleSettingsRepository()
    private lateinit var backup: String
    @Before fun setup() { instrumentation.runOnMainSync { backup = repository.checkpoint(); ReadBookConfig.shareLayout = false; ReadBookConfig.styleSelect = 0 } }
    @After fun teardown() { instrumentation.runOnMainSync { repository.restore(backup) } }
    @Test fun actualSliderWritesKeepLegacyConfigurationNumbersAndArrayListEventPayloads() {
        val latch = CountDownLatch(4); val events = mutableListOf<ArrayList<Int>>()
        val observer = Observer<ArrayList<Int>> { events += it; latch.countDown() }
        instrumentation.runOnMainSync {
            eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).observeForever(observer)
            listOf(ReadStyleSlider.TextSize to 45, ReadStyleSlider.LetterSpacing to 0,
                ReadStyleSlider.LineSpacing to 0, ReadStyleSlider.ParagraphSpacing to 20).forEach { (slider, value) ->
                repository.dispatch(repository.slider(slider, value))
            }
            assertEquals(50, ReadBookConfig.textSize); assertEquals(-.5f, ReadBookConfig.letterSpacing, 0f)
            assertEquals(-10, ReadBookConfig.lineSpacingExtra); assertEquals(20, ReadBookConfig.paragraphSpacing)
        }
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(List(4) { arrayListOf(8, 5) }, events) }
        finally { instrumentation.runOnMainSync { eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).removeObserver(observer) } }
    }
    @Test fun fontWeightChineseAndAllFiveIndentChoicesHaveOriginalValuesAndPayloads() {
        instrumentation.runOnMainSync {
            assertEquals(listOf(8, 9, 6), repository.weight(2).codes); assertEquals(2, ReadBookConfig.textBold)
            assertEquals(listOf(5), repository.chinese(1).codes)
            (0..4).forEach { count -> assertEquals(listOf(8, 5), repository.indent(count).codes); assertEquals("　".repeat(count), ReadBookConfig.paragraphIndent) }
            assertEquals(listOf(2, 5), repository.font("").codes)
            assertEquals(listOf(2, 5), repository.font("").codes)
            repository.font("test.ttf"); assertTrue(repository.font("test.ttf").codes.isEmpty())
        }
    }
    @Test fun checkpointRestoresPresetFieldsSharedConfigurationAndAddedPresetWithoutDispatch() {
        instrumentation.runOnMainSync {
            ReadBookConfig.shareLayout = true; repository.slider(ReadStyleSlider.TextSize, 30)
            ReadBookConfig.shareLayout = false; repository.slider(ReadStyleSlider.TextSize, 25)
            val index = repository.addPreset(); repository.select(index); repository.slider(ReadStyleSlider.LineSpacing, 45)
            val snapshot = repository.load(); val checkpoint = repository.checkpoint()
            repository.select(0); repository.slider(ReadStyleSlider.LineSpacing, 10); ReadBookConfig.shareConfig.textSize = 10
            repository.restore(checkpoint)
            assertEquals(snapshot, repository.load()); assertEquals(35, ReadBookConfig.shareConfig.textSize)
        }
    }
}
