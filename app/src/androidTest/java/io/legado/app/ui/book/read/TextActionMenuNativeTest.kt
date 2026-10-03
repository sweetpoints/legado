package io.legado.app.ui.book.read

import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.*
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TextActionMenuNativeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalConfig: String? = null
    private var originalMode = 0

    @Before
    fun setup() {
        originalConfig = context.getPrefString(PreferKey.textSelectMenuConfig)
        originalMode = AppConfig.contentSelectSpeakMod
        context.putPrefString(
            PreferKey.textSelectMenuConfig,
            TextSelectMenuConfig.default().toJson(),
        )
    }

    @After
    fun restore() {
        context.putPrefString(PreferKey.textSelectMenuConfig, originalConfig)
        AppConfig.contentSelectSpeakMod = originalMode
    }

    @Test
    fun realPopupUsesOriginalThreeWindowCoordinatesAndDisposesCompositionOnDismissAndDestroy() {
        lateinit var popup: TextActionMenu
        var made = false
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            fun show(top: Int, startBottom: Int, endBottom: Int, gravity: Int, y: Int) {
                scenario.onActivity { activity ->
                    if (!made) {
                        popup = TextActionMenu(activity, Callback())
                        made = true
                    }
                    popup.show(activity.window.decorView, 1200, 10, top, startBottom, 40, endBottom)
                }
                compose.waitUntil {
                    compose
                        .onAllNodesWithTag("text-action-builtin:copy")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                scenario.onActivity {
                    val params =
                        popup.contentView.rootView.layoutParams as WindowManager.LayoutParams
                    assertEquals(gravity, params.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                    assertEquals(y, params.y)
                    assertEquals(
                        if (gravity == Gravity.BOTTOM || endBottom - startBottom > 500) 10 else 40,
                        params.x,
                    )
                    assertFalse(popup.isFocusable)
                    assertFalse(popup.isOutsideTouchable)
                    assertTrue(popup.isTouchable)
                    assertTrue((popup.contentView as ComposeView).hasComposition)
                    popup.dismiss()
                    assertFalse((popup.contentView as ComposeView).hasComposition)
                }
            }
            show(800, 820, 860, Gravity.BOTTOM, 400)
            show(20, 100, 601, Gravity.TOP, 100)
            show(20, 100, 400, Gravity.TOP, 400)
            scenario.onActivity { popup.show(it.window.decorView, 1200, 10, 800, 820, 40, 860) }
            compose.waitUntil {
                compose
                    .onAllNodesWithTag("text-action-builtin:copy")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            scenario.recreate()
            assertFalse(popup.isShowing)
            assertFalse((popup.contentView as ComposeView).hasComposition)
        }
    }

    @Test
    fun actualCopyDispatchRetainsReaderResourceIdAndFinallyWhileMoreLongPressOnlyEdits() {
        lateinit var popup: TextActionMenu
        val callback = Callback()
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                popup = TextActionMenu(it, callback)
                callback.finallyAction = { popup.dismiss() }
                popup.show(it.window.decorView, it.window.decorView.height, 10, 800, 820, 40, 860)
            }
            compose.waitUntil {
                compose
                    .onAllNodesWithTag("text-action-builtin:copy")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("text-action-builtin:copy").performClick()
            compose.waitUntil { callback.finallyCount == 1 }
            assertEquals(listOf(R.id.menu_copy), callback.ids)
            scenario.onActivity { activity ->
                assertEquals(
                    callback.selectedText,
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString(),
                )
                popup.show(
                    activity.window.decorView,
                    activity.window.decorView.height,
                    10,
                    800,
                    820,
                    40,
                    860,
                )
            }
            compose.waitUntil {
                compose.onAllNodesWithTag("text-action-more").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("text-action-more").performTouchInput { longClick() }
            compose.waitUntil { callback.edits == 1 }
            assertEquals(1, callback.finallyCount)
            assertFalse(popup.isShowing)
        }
    }

    @Test
    fun platformIntentsKeepBrowserUrlSearchAndExplicitWritableProcessTextMetadata() {
        val browser = TextAction("browser", TextActionKind.Browser, "Browser")
        val url = textActionIntent(browser, "https://example.com/path?q=1")!!
        assertEquals(Intent.ACTION_VIEW, url.action)
        assertEquals("https://example.com/path?q=1", url.dataString)
        val search = textActionIntent(browser, "selected words")!!
        assertEquals(Intent.ACTION_WEB_SEARCH, search.action)
        assertEquals("selected words", search.getStringExtra(SearchManager.QUERY))
        val process =
            TextAction(
                "process",
                TextActionKind.ProcessText,
                "External",
                TextProcessTarget("external.package", "external.Activity", "External"),
            )
        val intent = textActionIntent(process, "payload")!!
        assertEquals(Intent.ACTION_PROCESS_TEXT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("external.package", intent.component!!.packageName)
        assertEquals("external.Activity", intent.component!!.className)
        assertEquals("payload", intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
    }

    private class Callback : TextActionMenu.CallBack {
        override val selectedText = "Native popup clipboard fixture"
        val ids = mutableListOf<Int>()
        var finallyCount = 0
        var edits = 0
        var finallyAction: () -> Unit = {}

        override fun onMenuItemSelected(itemId: Int): Boolean {
            ids += itemId
            return false
        }

        override fun onMenuActionFinally() {
            finallyCount++
            finallyAction()
        }

        override fun onEditTextActionMenu() {
            edits++
        }
    }
}
