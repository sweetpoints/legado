package io.legado.app.ui.book.read.config

import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.eventObservable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BgTextSettingsRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val repository = AppBgTextSettingsRepository(instrumentation.targetContext)
    private lateinit var backup: String
    private var comic = false

    @Before
    fun setup() {
        instrumentation.runOnMainSync {
            comic = ReadBookConfig.isComic
            backup = repository.checkpoint()
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
            ReadBookConfig.shareLayout = false
            ReadBookConfig.shareConfig = ReadBookConfig.shareConfig.copy()
        }
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync {
            repository.restore(backup)
            ReadBookConfig.isComic = comic
        }
    }

    @Test
    fun sharedLayoutKeepsDurNamesStatusBackgroundAndGlobalUnderlineSeparateFromSharedAlphaReview() {
        instrumentation.runOnMainSync {
            ReadBookConfig.shareLayout = true
            ReadBookConfig.shareConfig.name = "Shared"
            ReadBookConfig.shareConfig.setCurStatusIconDark(true)
            ReadBookConfig.durConfig.bgAlpha = 91
            ReadBookConfig.durConfig.reviewIconSvg = "Dur SVG"
            assertTrue(repository.set(BgTextSetting.DarkStatus, "false").systemUi)
            repository.set(BgTextSetting.Name, "Dur name")
            repository.set(BgTextSetting.Alpha, "23")
            repository.set(BgTextSetting.AssetBackground, "paper.png")
            repository.set(BgTextSetting.ReviewSvg, "Shared SVG")
            assertEquals("Dur name", ReadBookConfig.durConfig.name)
            assertEquals("Shared", ReadBookConfig.shareConfig.name)
            assertFalse(ReadBookConfig.durConfig.curStatusIconDark())
            assertTrue(ReadBookConfig.shareConfig.curStatusIconDark())
            assertEquals(23, ReadBookConfig.shareConfig.bgAlpha)
            assertEquals(91, ReadBookConfig.durConfig.bgAlpha)
            assertEquals("Shared SVG", ReadBookConfig.shareConfig.reviewIconSvg)
            assertEquals("Dur SVG", ReadBookConfig.durConfig.reviewIconSvg)
            assertEquals(1, ReadBookConfig.durConfig.curBgType())
            assertEquals("paper.png", ReadBookConfig.durConfig.curBgStr())
            repository.set(BgTextSetting.UnderlineMode, "6")
            repository.set(BgTextSetting.UnderlineWidth, "20")
            repository.set(BgTextSetting.UnderlineDistance, "60")
            repository.set(BgTextSetting.UnderlineBody, "false")
            repository.set(BgTextSetting.UnderlineTitle, "false")
            repository.color(BgTextColor.Underline, 0x40ff0000)
            (ReadBookConfig.configList + ReadBookConfig.shareConfig).forEach { config ->
                assertEquals(6, config.underlineMode)
                assertEquals(10f, config.underlineWidth, 0f)
                assertEquals(30f, config.underlineDistance, 0f)
                assertFalse(config.underlineBodyEnabled)
                assertFalse(config.underlineTitleEnabled)
                assertTrue(config.underlineColorSet)
                assertEquals(0x40ff0000, config.underlineColor)
            }
        }
    }

    @Test
    fun allFiveColorsKeepActualFieldsAndExactArrayListEventPayloads() {
        val events = mutableListOf<ArrayList<Int>>()
        val latch = CountDownLatch(5)
        val observer =
            Observer<ArrayList<Int>> {
                events += it
                latch.countDown()
            }
        instrumentation.runOnMainSync {
            eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).observeForever(observer)
            listOf(
                    BgTextColor.Text,
                    BgTextColor.Background,
                    BgTextColor.Accent,
                    BgTextColor.Review,
                    BgTextColor.Underline,
                )
                .forEach {
                    repository.dispatch(repository.color(it, 0xff123456.toInt()))
                }
            assertEquals(0xff123456.toInt(), ReadBookConfig.durConfig.curTextColor())
            assertEquals(0xff123456.toInt(), ReadBookConfig.durConfig.curTextAccentColor())
            assertEquals(0, ReadBookConfig.durConfig.curBgType())
            assertEquals("#ff123456", ReadBookConfig.durConfig.curBgStr().lowercase())
            assertEquals(0xff123456.toInt(), ReadBookConfig.reviewIconColor)
            assertEquals(0xff123456.toInt(), ReadBookConfig.underlineColor)
            assertTrue(ReadBookConfig.underlineColorSet)
        }
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    arrayListOf(2, 6, 9, 11),
                    arrayListOf(1),
                    arrayListOf(2, 6, 9, 11),
                    arrayListOf(8, 9, 11),
                    arrayListOf(6, 9, 11),
                ),
                events,
            )
        } finally {
            instrumentation.runOnMainSync {
                eventObservable<ArrayList<Int>>(EventBus.UP_CONFIG).removeObserver(observer)
            }
        }
    }

    @Test
    fun checkpointRestoresBothSelectionIndicesAndFinishedViewModelNeverRestoresOldGlobalState() {
        instrumentation.runOnMainSync {
            ReadBookConfig.readStyleSelect = 1
            ReadBookConfig.comicStyleSelect = 2
            ReadBookConfig.shareLayout = true
            repository.set(BgTextSetting.Name, "Restored preset")
            repository.set(BgTextSetting.Alpha, "42")
            val checkpoint = repository.checkpoint()
            ReadBookConfig.readStyleSelect = 0
            ReadBookConfig.comicStyleSelect = 0
            ReadBookConfig.shareLayout = false
            repository.restore(checkpoint)
            assertEquals(1, ReadBookConfig.readStyleSelect)
            assertEquals(2, ReadBookConfig.comicStyleSelect)
            assertTrue(ReadBookConfig.shareLayout)
            assertEquals("Restored preset", ReadBookConfig.durConfig.name)
            assertEquals(42, ReadBookConfig.bgAlpha)
            repository.set(BgTextSetting.Name, "New global preset")
            val files =
                object : ReaderBackgroundFilesRepository {
                    override suspend fun export(
                        snapshot: ReaderBackgroundExportSnapshot,
                        directory: String,
                    ): String = error("closed")

                    override suspend fun importFile(uri: String): String = error("closed")

                    override suspend fun importUrl(url: String): String = error("closed")

                    override suspend fun storeBackground(uri: String): String = error("closed")
                }
            val model =
                BgTextSettingsViewModel(
                    repository,
                    files,
                    SavedStateHandle(
                        mapOf("bgText.finished" to true, "bgText.checkpoint" to checkpoint)
                    ),
                )
            model.dismissed(false)
            assertEquals("New global preset", ReadBookConfig.durConfig.name)
            assertTrue(model.state.value.finished)
            model.stop()
        }
    }

    @Test
    fun svgValidationAndTemplateMutationUseRealParserWhileExportCapturesDistinctSharedFonts() =
        runBlocking {
            val svg =
                """<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><rect width="48" height="48" fill="#f00"/><text x="0" y="24">{{count}}</text></svg>"""
            assertTrue(repository.validSvg(svg))
            assertFalse(repository.validSvg("invalid"))
            assertFalse(
                repository.validSvg(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1"><rect width="1000" height="1"/></svg>"""
                )
            )
            instrumentation.runOnMainSync {
                repository.putTemplate("First", svg)
                repository.putTemplate("Renamed", svg)
                assertEquals(1, repository.load().templates.count { it.svg == svg })
                assertEquals("Renamed", repository.load().templates.first { it.svg == svg }.name)
                repository.removeTemplate(svg)
                assertFalse(repository.load().templates.any { it.svg == svg })
                ReadBookConfig.shareLayout = true
                ReadBookConfig.textFont = "body.ttf"
                ReadBookConfig.titleFont = "title.ttf"
                val export = repository.exportSnapshot()
                assertEquals("body.ttf", export.textFont)
                assertEquals("title.ttf", export.titleFont)
            }
        }
}
