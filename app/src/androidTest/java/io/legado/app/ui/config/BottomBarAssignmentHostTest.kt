package io.legado.app.ui.config

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.BottomBarSkinManager
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BottomBarAssignmentHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var session: String
    private lateinit var scenario: ActivityScenario<BottomBarSkinAssignActivity>

    @Before
    fun setup() {
        session =
            runBlocking(Dispatchers.IO) {
                val bitmap =
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(0xff3276ab.toInt())
                    }
                val png =
                    ByteArrayOutputStream()
                        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        .toByteArray()
                bitmap.recycle()
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { zip ->
                    listOf("home_selected.png", "home_normal.png").forEach { name ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(png)
                        zip.closeEntry()
                    }
                }
                BottomBarSkinManager.extractImages(output.toByteArray().inputStream()).getOrThrow()
            }
        scenario =
            ActivityScenario.launch(
                Intent(context, BottomBarSkinAssignActivity::class.java)
                    .putExtra("sessionId", session)
                    .putExtra("name", "Initial")
            )
        compose.onNodeWithTag("bar-assignment-name").assertExists()
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.model.state.value.loaded }
            loaded
        }
    }

    @After
    fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) { BottomBarSkinManager.discardSession(session) }
    }

    @Test
    fun actualActivityRecreationPreservesExplicitClearNameAndUncommittedStagingFiles() {
        compose.onNodeWithTag("bar-assignment-name").performTextReplacement("Edited")
        compose.onNodeWithTag("bar-assignment-home-selected").performClick()
        compose.onNodeWithTag("bar-assignment-clear").performClick()
        scenario.recreate()
        compose.onNodeWithTag("bar-assignment-name").assertTextContains("Edited")
        compose.waitUntil {
            var loaded = false
            scenario.onActivity { loaded = it.model.state.value.loaded }
            loaded
        }
        scenario.onActivity {
            assertNull(it.model.state.value.slots.single { row -> row.slot == "home" }.selected)
            assertNull(it.model.state.value.slots.single { row -> row.slot == "home" }.normal)
        }
        assertEquals(
            2,
            runBlocking(Dispatchers.IO) { BottomBarSkinManager.stagingImages(session).size },
        )
    }

    @Test
    fun cancelingPaletteLeavesStagingAndHostCloseDiscardsSession() {
        compose.onNodeWithTag("bar-assignment-home-normal").performClick()
        scenario.onActivity { it.model.cancelPalette() }
        compose.onNodeWithTag("bar-assignment-palette").assertDoesNotExist()
        assertEquals(
            2,
            runBlocking(Dispatchers.IO) { BottomBarSkinManager.stagingImages(session).size },
        )
        compose.onNodeWithTag("bar-assignment-back").performClick()
        compose.waitUntil {
            runBlocking(Dispatchers.IO) { BottomBarSkinManager.stagingImages(session).isEmpty() }
        }
    }
}
