package io.legado.app.ui.rss.favorites

import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.model.VideoPlay
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.video.VideoPlayerActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Metadata entrypoint test. The owned local media fixture never requests a network stream. */
class RssFavoriteConfigVideoHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val driver = FlutterUiTestDriver(instrumentation)
    @Test fun videoFavoriteToolbarUsesFlutterDialogAndCancelKeepsTheNewFavorite() {
        val origin = "https://favorite-video-${UUID.randomUUID()}.invalid"
        val media = File(context.cacheDir, "favorite-video-${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf()) }
        val link = media.toURI().toString()
        val originalFloat = VideoPlay.defaultFloatWindow
        val originalSource = VideoPlay.source
        val originalBook = VideoPlay.book
        val originalStar = VideoPlay.rssStar
        val originalRecord = VideoPlay.rssRecord
        val originalUrl = VideoPlay.videoUrl
        val originalTitle = VideoPlay.videoTitle
        val originalSingle = VideoPlay.singleUrl
        val originalToc = VideoPlay.toc
        val originalVolumes = ArrayList(VideoPlay.volumes)
        val originalEpisodes = VideoPlay.episodes
        val originalDurVolume = VideoPlay.durVolume
        val originalChapterIndex = VideoPlay.chapterInVolumeIndex
        val originalVolumeIndex = VideoPlay.durVolumeIndex
        val originalPosition = VideoPlay.durChapterPos
        val originalShelf = VideoPlay.inBookshelf
        var requestId: String? = null
        var scenario: ActivityScenario<VideoPlayerActivity>? = null
        try {
            driver.enableAccessibility()
            VideoPlay.defaultFloatWindow = false
            runBlocking(Dispatchers.IO) {
                appDb.rssSourceDao.insert(RssSource(sourceUrl = origin, sourceName = "Video favorite fixture"))
                appDb.rssReadRecordDao.insertRecord(RssReadRecord(record = link, origin = origin,
                    title = "Original video", type = 2, durPos = 73))
            }
            val launched = ActivityScenario.launch<VideoPlayerActivity>(Intent(context, VideoPlayerActivity::class.java)
                .putExtra("sourceKey", origin).putExtra("sourceType", SourceType.rss)
                .putExtra("record", link).putExtra("videoUrl", link).putExtra("videoTitle", "Original video"))
            scenario = launched
            compose.onNodeWithTag("video-toolbar-favorite").performClick()
            driver.awaitIdentifier("favorite-config-title")
            launched.onActivity { activity ->
                val dialog = activity.supportFragmentManager.fragments.filterIsInstance<RssFavoritesDialog>().single()
                requestId = dialog.requireArguments().getString("requestId")
                assertTrue(dialog.childFragmentManager.fragments.any { it is RssFavoriteConfigFlutterFragment })
            }
            assertNotNull(runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) })
            driver.setText("favorite-config-title", "Cancelled video edit")
            closeSoftKeyboard(); driver.click("favorite-config-cancel")
            driver.await("Video dialog cancelled without deleting its created favorite") {
                var closed = false
                launched.onActivity { activity ->
                    closed = activity.supportFragmentManager.fragments.none { it is RssFavoritesDialog }
                }
                closed
            }
            val star = runBlocking(Dispatchers.IO) { appDb.rssStarDao.get(origin, link) }!!
            assertEquals("Original video", star.title)
            assertEquals(2, star.type)
            assertEquals(origin, star.origin)
        } finally {
            scenario?.close(); driver.close()
            runBlocking(Dispatchers.IO) {
                appDb.rssStarDao.delete(origin, link)
                appDb.rssReadRecordDao.deleteRecordsByOrigin(origin)
                appDb.rssSourceDao.delete(origin)
            }
            instrumentation.runOnMainSync {
                VideoPlay.source = originalSource; VideoPlay.book = originalBook
                VideoPlay.rssStar = originalStar; VideoPlay.rssRecord = originalRecord
                VideoPlay.videoUrl = originalUrl; VideoPlay.videoTitle = originalTitle
                VideoPlay.singleUrl = originalSingle
                VideoPlay.toc = originalToc; VideoPlay.volumes = originalVolumes
                VideoPlay.episodes = originalEpisodes; VideoPlay.durVolume = originalDurVolume
                VideoPlay.chapterInVolumeIndex = originalChapterIndex
                VideoPlay.durVolumeIndex = originalVolumeIndex
                VideoPlay.durChapterPos = originalPosition; VideoPlay.inBookshelf = originalShelf
                VideoPlay.defaultFloatWindow = originalFloat
            }
            requestId?.let { File(context.filesDir, "rss-favorite-config/$it.json").delete() }
            media.delete()
        }
    }
}
