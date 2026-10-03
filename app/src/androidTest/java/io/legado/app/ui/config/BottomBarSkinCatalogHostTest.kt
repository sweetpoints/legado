package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.*
import io.legado.app.help.BottomBarSkinManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BottomBarSkinCatalogHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var scenario: ActivityScenario<BottomBarSkinActivity>
    private lateinit var name: String
    private var previousActive = ""

    @Before
    fun setup() {
        runBlocking(Dispatchers.IO) {
            previousActive = BottomBarSkinManager.active
            val bitmap =
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xff3a6ab8.toInt())
                }
            val png =
                ByteArrayOutputStream()
                    .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            bitmap.recycle()
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("home_selected.png"))
                zip.write(png)
                zip.closeEntry()
            }
            val session =
                BottomBarSkinManager.extractImages(bytes.toByteArray().inputStream()).getOrThrow()
            try {
                val repo = AppBottomBarAssignmentRepository()
                name =
                    repo.save(
                        session,
                        "CatalogHost-${UUID.randomUUID()}",
                        null,
                        repo.load(session, 56).slots,
                    )
            } finally {
                BottomBarSkinManager.discardSession(session)
            }
        }
        scenario = ActivityScenario.launch(Intent(context, BottomBarSkinActivity::class.java))
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.model.state.value.loaded }
            loaded
        }
    }

    @After
    fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) {
            BottomBarSkinManager.delete(name)
            BottomBarSkinManager.active = previousActive
        }
    }

    @Test
    fun selectingDefaultUpdatesActualManagerAndLongPressMenuRestoresAfterActivityRecreation() {
        compose.onNodeWithTag("skin-catalog-default").performClick()
        compose.waitUntil { runBlocking(Dispatchers.IO) { BottomBarSkinManager.active.isEmpty() } }
        compose
            .onNodeWithTag("skin-catalog-grid")
            .performScrollToNode(hasTestTag("skin-catalog-item-$name"))
        compose.onNodeWithTag("skin-catalog-item-$name").performTouchInput { longClick() }
        compose.onNodeWithTag("skin-catalog-menu-delete").assertExists()
        scenario.recreate()
        compose.onNodeWithTag("skin-catalog-menu-delete").assertExists()
        compose.onNodeWithTag("skin-catalog-menu-cancel").performClick()
        assertTrue(runBlocking(Dispatchers.IO) { BottomBarSkinManager.hasSkin(name) })
    }

    @Test
    fun cancelDeleteLeavesActualSkinAndConfirmRemovesOnlyFixtureAndRefreshesGrid() {
        scenario.onActivity { it.model.requestDelete(name) }
        compose.onNodeWithTag("skin-catalog-delete-cancel").performClick()
        assertTrue(runBlocking(Dispatchers.IO) { BottomBarSkinManager.hasSkin(name) })
        scenario.onActivity { it.model.requestDelete(name) }
        compose.onNodeWithTag("skin-catalog-delete-confirm").performClick()
        compose.waitUntil { runBlocking(Dispatchers.IO) { !BottomBarSkinManager.hasSkin(name) } }
        compose.onNodeWithTag("skin-catalog-item-$name").assertDoesNotExist()
    }

    @Test
    fun nativeBackCannotExitBetweenActivationAndChangedEventDelivery() {
        scenario.onActivity {
            it.model.activate("")
            assertTrue(it.model.state.value.closeBlocked)
            it.onBackPressedDispatcher.onBackPressed()
            assertFalse(it.isFinishing)
        }
        compose.waitUntil {
            var delivered = false
            scenario.onActivity {
                delivered =
                    !it.model.state.value.closeBlocked && it.model.state.value.effect == null
            }
            delivered
        }
        compose.onNodeWithTag("skin-catalog-back").assertIsEnabled()
    }
}
