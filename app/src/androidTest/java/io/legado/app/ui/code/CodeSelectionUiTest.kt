package io.legado.app.ui.code

import android.app.Activity
import android.app.Instrumentation
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import io.legado.app.ci.closeAfterComposeExit
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.event.HandleStateChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.association.BookImportViewModel
import io.legado.app.ui.association.launchAssociation
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.RssImportViewModel
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CodeSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cacheKey = "code-selection-${UUID.randomUUID()}"
    private val source =
        "const before = 1;\nfunction example() {\n  const message = \"你好 🌍\";\n  return message;\n}\nconst after = 2;"
    private var scenario: ActivityScenario<CodeEditActivity>? = null
    private val selectedText =
        source.substring(source.indexOf("function"), source.indexOf("\nconst after"))

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync {
            // Read lifecycle and reset the draft in one main-thread turn: a finished result can
            // arrive before onDestroy, racing a separate scenario.state / onActivity pair.
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap(monitor::getActivitiesInStage)
                .filterIsInstance<CodeEditActivity>()
                .filterNot { it.isFinishing }
                .forEach { activity ->
                    // Test cleanup explicitly discards unsaved native or WebView drafts.
                    activity.findViewById<CodeEditor>(R.id.editText)?.let { actions(it).dismiss() }
                    ViewModelProvider(activity)[CodeEditorComposeViewModel::class.java].discard()
                }
        }
        scenario?.closeAfterComposeExit(compose)
        CacheManager.deleteMemory(cacheKey)
    }

    @Test
    fun longPressKeepsMultilineSelectionAndSharesExactlyThatText() {
        launchEditor()
        selectFunction()
        val expectedLeft = source.indexOf("function")
        val expectedRight = source.indexOf("\nconst after")
        for ((line, column) in listOf(2 to 11, 3 to 5)) {
            dismissNativeMenu()
            // Separate independent long presses from the platform's double-tap gesture window.
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            press(Tap.LONG, line, column)
            try {
                awaitEditor {
                    it.cursor.left == expectedLeft &&
                        it.cursor.right == expectedRight &&
                        !actions(it).isEnabled &&
                        !actions(it).isShowing
                }
            } catch (failure: AssertionError) {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(
                            context.getExternalFilesDir("ui-regression"),
                            "code-selection-failed-$line-$column.png",
                        )
                        .outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
                withEditor {
                    throw AssertionError(
                        "Long press at $line:$column: selection=${it.cursor.left}..${it.cursor.right}, " +
                            "expected=$expectedLeft..$expectedRight, panel=${actions(it).isShowing}",
                        failure,
                    )
                }
            }
            withEditor { assertEquals(source, it.text.toString()) }
            assertNativeMenu()
        }
        screenshot("code-selection-preserved-share")
        shareAndAssert(selectedText)
        withEditor {
            assertEquals(expectedLeft, it.cursor.left)
            assertEquals(expectedRight, it.cursor.right)
            assertEquals(source, it.text.toString())
        }
    }

    @Test
    fun longPressOutsideSelectionSelectsAndSharesTheNewWord() {
        assertOutsideSelectionReselects(readOnly = false)
    }

    @Test
    fun readOnlyLongPressOutsideSelectionSelectsAndSharesTheNewWord() {
        assertOutsideSelectionReselects(readOnly = true)
    }

    private fun assertOutsideSelectionReselects(readOnly: Boolean) {
        launchEditor(readOnly)
        for ((line, column, word) in listOf(Triple(0, 8, "before"), Triple(5, 8, "after"))) {
            selectFunction()
            dismissNativeMenu()
            press(Tap.LONG, line, column)
            awaitEditor {
                selection(it) == word && !actions(it).isEnabled && !actions(it).isShowing
            }
            withEditor {
                assertEquals(source.indexOf(word), it.cursor.left)
                assertEquals(source.indexOf(word) + word.length, it.cursor.right)
                assertEquals(source, it.text.toString())
            }
            assertNativeMenu()
            screenshot("code-selection-outside-$word-${if (readOnly) "readonly" else "editable"}")
            shareAndAssert(word)
        }
    }

    @Test
    fun firstLongPressStillSelectsWordAndTapClearsSelection() {
        launchEditor()
        withEditor {
            it.setSelection(0, 0)
            actions(it).dismiss()
        }
        press(Tap.LONG, 2, 11)
        awaitEditor {
            it.cursor.isSelected && selection(it) == "message" && !actions(it).isShowing
        }
        assertNativeMenu()
        screenshot("code-selection-first-long-press")
        dismissNativeMenu()
        // A normal tap still clears the selection.
        press(Tap.SINGLE, 0, 2)
        awaitEditor { !it.cursor.isSelected && actions(it).isEnabled }
        withEditor { assertEquals(source, it.text.toString()) }
    }

    @Test
    fun readOnlyCodeCanShareSelectionWithoutExposingCutOrPaste() {
        launchEditor(readOnly = true)
        selectFunction()
        withEditor {
            assertFalse(it.isEditable)
            assertTrue(actions(it).isShowing)
            assertFalse(
                actions(it)
                    .view
                    .findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_cut)
                    .isVisible
            )
            assertFalse(
                actions(it)
                    .view
                    .findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_paste)
                    .isVisible
            )
        }
        screenshot("code-selection-read-only-share")
        shareAndAssert(selectedText)
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        onView(withText(android.R.string.cut)).inRoot(isPlatformPopup()).check(doesNotExist())
        onView(withText(android.R.string.paste)).inRoot(isPlatformPopup()).check(doesNotExist())
        screenshot("code-selection-read-only-native")
        shareAndAssert(selectedText)
        withEditor { assertEquals(source, it.text.toString()) }
    }

    @Test
    fun searchResultLongPressThenOutsideReselects() {
        launchEditor()
        compose.onNodeWithTag("code-search-toggle").performClick()
        compose.onNodeWithTag("code-query").performTextReplacement("function")
        closeSoftKeyboard()
        awaitEditor { it.searcher.hasQuery() && it.searcher.matchedPositionCount > 0 }
        withEditor { assertTrue(it.searcher.gotoNext()) }
        awaitEditor { selection(it) == "function" }

        press(Tap.LONG, 1, 3)
        awaitEditor { selection(it) == "function" && !actions(it).isShowing }
        assertNativeMenu()
        dismissNativeMenu()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)

        press(Tap.LONG, 0, 8)
        awaitEditor { selection(it) == "before" && !actions(it).isShowing }
        assertNativeMenu()
    }

    @Test
    fun nativeCopyCutPasteAndSelectAllUseTheCurrentSelection() {
        launchEditor()
        lateinit var clipboard: ClipboardManager
        withEditor {
            clipboard = it.context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("before copy", "clipboard sentinel"))
        }
        awaitEditor {
            it.hasWindowFocus() &&
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "clipboard sentinel"
        }
        selectFunction(native = true)
        clickNativeAction(android.R.string.copy)
        awaitEditor {
            !it.cursor.isSelected &&
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == selectedText
        }
        withEditor {
            assertEquals(selectedText, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(source, it.text.toString())
            assertFalse(it.cursor.isSelected)
        }
        selectFunction(native = true)
        clickNativeAction(android.R.string.cut)
        val withoutFunction = source.replace(selectedText, "")
        awaitEditor {
            it.text.toString() == withoutFunction &&
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == selectedText
        }
        withEditor {
            assertEquals(selectedText, clipboard.primaryClip!!.getItemAt(0).text.toString())
            it.setText(source)
            clipboard.setPrimaryClip(ClipData.newPlainText("native paste", "replacement 中文"))
        }
        selectFunction(native = true)
        clickNativeAction(android.R.string.paste)
        awaitEditor { it.text.toString() == source.replace(selectedText, "replacement 中文") }
        withEditor { it.setText(source) }
        selectFunction(native = true)
        clickNativeAction(android.R.string.selectAll)
        awaitEditor { selection(it) == source }
        screenshot("code-selection-native-select-all")
    }

    @Test
    fun nativeSearchSharesOnlySelectedTextAndBackKeepsTheEditorOpen() {
        launchEditor()
        selectFunction(native = true)
        val search = AtomicReference<Intent?>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_WEB_SEARCH) return null
                    search.set(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            clickNativeAction(R.string.search)
            await { search.get() != null }
            assertEquals(selectedText, search.get()!!.getStringExtra(SearchManager.QUERY))
            withEditor { assertEquals(source, it.text.toString()) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        pressBack()
        withEditor {
            assertEquals(source, it.text.toString())
            assertEquals(selectedText, selection(it))
        }
        onView(withText(android.R.string.copy)).check(doesNotExist())
    }

    @Test
    fun longPressDragExtendsTheSelectionAndReturnsToTheEditorToolbar() {
        launchEditor()
        val from = editorPoint(2, 11)
        val to = editorPoint(3, 15)
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, point: FloatArray) {
            val event =
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, from)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        awaitEditor { selection(it) == "message" }
        touch(MotionEvent.ACTION_MOVE, to)
        touch(MotionEvent.ACTION_UP, to)
        awaitEditor {
            it.cursor.left == source.indexOf("message") &&
                selection(it).contains("\n  return") &&
                it.text.toString() == source
        }
        assertEditorToolbar()
        screenshot("code-selection-editor-drag")
    }

    @Test
    fun nativeMenuStaysClearOfKeyboardAndSearchToolsNearTheBottomAfterScrolling() {
        launchEditor()
        val code = (0..80).joinToString("\n") { "const line$it = $it;" }
        scenario!!.onActivity { activity ->
            activity.findViewById<CodeEditor>(R.id.editText).apply {
                setText(code)
                // The hosted emulator reports a hardware keyboard; allow the phone's IME path.
                setDisableSoftKbdIfHardKbdAvailable(false)
            }
        }
        compose.onNodeWithTag("code-search-toggle").performClick()
        onView(withId(R.id.editText)).perform(click())
        withEditor { it.showSoftInput() }
        awaitEditor {
            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        var selected = ""
        var offset = 0
        var selectedLine = 0
        withEditor {
            val end = it.lastVisibleLine - 1
            assertTrue(
                "The fixture must leave room for a multiline selection",
                end >= it.firstVisibleLine + 2,
            )
            it.setSelectionRegion(end - 2, 0, end, 10, false)
            selected = selection(it)
            offset = it.offsetY
            selectedLine = end - 1
        }
        assertEditorToolbar()
        dismissEditorToolbar()
        press(Tap.LONG, selectedLine, 3)
        assertNativeMenu()
        assertNativeMenuClearOfTools()
        screenshot("code-selection-native-ime-search-bottom")
        val nativeMenu = Rect()
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check { view, failure ->
            if (failure != null) throw failure
            // Android's popup window also reserves transparent space for its closed overflow panel.
            visibleScreenBounds(view!!.parent.parent as View, nativeMenu)
        }
        fun scrollPoint(view: View, rowsFromBottom: Float): FloatArray {
            val editor = view as CodeEditor
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val point =
                floatArrayOf(
                    position[0] + view.width * 0.9f,
                    position[1] + view.height - editor.rowHeight * rowsFromBottom,
                )
            assertFalse(
                "The scroll gesture must avoid the visible native menu $nativeMenu",
                nativeMenu.contains(point[0].toInt(), point[1].toInt()),
            )
            return point
        }
        var from = floatArrayOf()
        var to = floatArrayOf()
        withEditor {
            from = scrollPoint(it, 0.25f)
            to = scrollPoint(it, 2.25f)
        }
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, point: FloatArray) {
            val event =
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        // Move before a long press can select text, then stop before lifting to avoid a fling.
        touch(MotionEvent.ACTION_DOWN, from)
        touch(MotionEvent.ACTION_MOVE, to)
        SystemClock.sleep(150)
        touch(MotionEvent.ACTION_MOVE, to)
        touch(MotionEvent.ACTION_UP, to)
        var scrollState = ""
        await(message = { scrollState }) {
            var ready = false
            withEditor {
                scrollState =
                    "Scroll ${it.offsetY} > $offset; selection=${selection(it)}; expected=$selected"
                ready =
                    it.offsetY > offset && selection(it) == selected && it.text.toString() == code
            }
            ready
        }
        // FloatingActionMode deliberately hides a moving toolbar briefly before positioning it.
        SystemClock.sleep(500)
        assertNativeMenu()
        assertNativeMenuClearOfTools()
        screenshot("code-selection-native-ime-search-scrolled")
    }

    @Test
    fun draggingEitherSelectionHandleAfterLongPressRestoresEditorActions() {
        launchEditor()
        var heldType = -1
        withEditor {
            it.subscribeEvent(HandleStateChangeEvent::class.java) { event, _ ->
                if (event.isHeld) heldType = event.handleType
            }
        }
        for (rightHandle in listOf(true, false)) {
            withEditor {
                heldType = -1
                it.setSelection(0, 0)
            }
            dismissEditorToolbar()
            press(Tap.LONG, 2, 11)
            awaitEditor { selection(it) == "message" }
            assertNativeMenu()
            var from = floatArrayOf()
            var fixedEnd = -1
            var handleHeight = 0f
            withEditor {
                val handle = if (rightHandle) it.rightHandleDescriptor else it.leftHandleDescriptor
                val location = IntArray(2)
                it.getLocationOnScreen(location)
                assertFalse(handle.position.isEmpty)
                from =
                    floatArrayOf(
                        location[0] + handle.position.centerX(),
                        location[1] + handle.position.centerY(),
                    )
                handleHeight = handle.position.height()
                fixedEnd = if (rightHandle) it.cursor.left else it.cursor.right
            }
            val to = if (rightHandle) editorPoint(3, 15) else editorPoint(1, 9)
            // Sora places the caret one handle-height above the dragging finger.
            to[1] += handleHeight
            onView(withId(R.id.editText))
                .perform(GeneralSwipeAction(Swipe.SLOW, { from }, { to }, Press.FINGER))
            awaitEditor {
                !it.eventHandler.hasAnyHeldHandle() &&
                    selection(it).contains('\n') &&
                    actions(it).isEnabled &&
                    actions(it).isShowing
            }
            var draggedSelection = ""
            withEditor {
                assertEquals(
                    if (rightHandle) HandleStateChangeEvent.HANDLE_TYPE_RIGHT
                    else HandleStateChangeEvent.HANDLE_TYPE_LEFT,
                    heldType,
                )
                assertEquals(fixedEnd, if (rightHandle) it.cursor.left else it.cursor.right)
                assertEquals(source, it.text.toString())
                draggedSelection = selection(it)
            }
            assertEditorToolbar()
            screenshot(
                "code-selection-${if (rightHandle) "right" else "left"}-handle-editor-toolbar"
            )
            shareAndAssert(draggedSelection)
        }
    }

    @Test
    fun ordinarySelectionKeepsEditorActionsUntilLongPressAndReturnsAfterTap() {
        launchEditor()
        selectFunction()
        screenshot("code-selection-ordinary-editor-toolbar")
        dismissEditorToolbar()
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        withEditor { assertEquals(selectedText, selection(it)) }
        screenshot("code-selection-long-press-native-toolbar")
        dismissNativeMenu()
        press(Tap.SINGLE, 0, 2)
        awaitEditor { !it.cursor.isSelected && actions(it).isEnabled }
        selectFunction()
        assertEditorToolbar()
        shareAndAssert(selectedText)
    }

    @Test
    fun longPressAtAnEmptyCaretShowsNativePasteAndInsertsClipboardText() {
        launchEditor()
        val clipboardText = "native caret paste 中文"
        withEditor {
            it.setText("")
            it.context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("caret paste", clipboardText))
        }
        press(Tap.LONG, 0, 0)
        awaitEditor { !it.cursor.isSelected && !actions(it).isEnabled && !actions(it).isShowing }
        onView(withText(android.R.string.paste))
            .inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check(doesNotExist())
        screenshot("code-selection-empty-native-paste")
        clickNativeAction(android.R.string.paste)
        awaitEditor { it.text.toString() == clipboardText && actions(it).isEnabled }
    }

    @Test
    fun savedDraftUsesTheCallersRequestedTransport() {
        val large = "中文 draft 🌍\n".repeat(2_000)
        for ((fileMode, expected) in listOf(true to large, false to large, true to "短文本")) {
            var returnedFile: String? = null
            try {
                launchEditor(forResult = true, fileMode = fileMode)
                withEditor { it.setText(expected) }
                awaitEditor { it.text.toString() == expected }
                compose.onNodeWithTag("code-save").performClick()
                await { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                val result = scenario!!.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                val data = checkNotNull(result.resultData)
                returnedFile = data.getStringExtra("textFile")
                if (fileMode) {
                    assertFalse(
                        "File-transport drafts must stay out of the result Bundle",
                        data.hasExtra("text"),
                    )
                    assertNotNull(returnedFile)
                    assertEquals(expected, CodeTextTransfer.read(context, returnedFile!!))
                } else {
                    assertNull(returnedFile)
                    assertEquals(expected, data.getStringExtra("text"))
                }
            } finally {
                CodeTextTransfer.delete(context, returnedFile)
                scenario?.closeAfterComposeExit(compose)
                scenario = null
            }
        }
    }

    @Test
    fun sourcePreviewEditsReturnToTheSameBookAndRssCandidateWithoutLosingLongCode() {
        val id = UUID.randomUUID().toString()
        val script = "// 中文 🌍 original draft\n".repeat(2_000)
        val savedReplacement = AppConfig.importReplaceSource
        val rule =
            ReplaceRule(
                name = "Preview editor $id",
                pattern = "#edited",
                replacement = "#edited-once",
                isRegex = false,
                scopeSource = true,
                scopeContent = false,
            )
        appDb.replaceRuleDao.insert(rule)
        try {
            for ((rss, replacements) in
                listOf(false to false, true to false, false to true, true to true)) {
                AppConfig.importReplaceSource = replacements
                val urls =
                    listOf(
                        "https://preview-$id.invalid/first",
                        "https://preview-$id.invalid/second",
                    )
                val sources =
                    if (rss)
                        urls.mapIndexed { index, url ->
                            RssSource(
                                sourceUrl = url,
                                sourceName = "Preview $index",
                                jsLib = script,
                                ruleArticles = "article",
                            )
                        }
                    else
                        urls.mapIndexed { index, url ->
                            BookSource(
                                bookSourceUrl = url,
                                bookSourceName = "Preview $index",
                                jsLib = script,
                                searchUrl = "/search",
                                ruleSearch = SearchRule(bookList = "article", name = "text"),
                            )
                        }
                val file =
                    File(context.cacheDir, "preview-editor-$id-$rss.json").apply {
                        writeText(GSON.toJson(sources))
                    }
                val uri =
                    FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
                val intent =
                    Intent(context, FileAssociationActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                var editorActivity: CodeEditActivity? = null
                try {
                    compose.launchAssociation<FileAssociationActivity>(intent).use { host ->
                        var parent: DialogFragment? = null
                        await {
                            host.onActivity { activity ->
                                parent =
                                    activity.supportFragmentManager.fragments
                                        .filterIsInstance<DialogFragment>()
                                        .find {
                                            if (rss) it is ImportRssSourceDialog
                                            else it is ImportBookSourceDialog
                                        }
                            }
                            var ready = false
                            instrumentation.runOnMainSync {
                                ready =
                                    (if (rss)
                                        parent?.let {
                                            ViewModelProvider(it)[RssImportViewModel::class.java]
                                                .state
                                                .value
                                                .let { value ->
                                                    value.interactive && value.items.size == 2
                                                }
                                        } == true
                                    else
                                        parent?.let {
                                            ViewModelProvider(it)[BookImportViewModel::class.java]
                                                .state
                                                .value
                                                .let { value ->
                                                    value.interactive && value.items.size == 2
                                                }
                                        } == true) &&
                                        parent?.dialog?.window?.decorView?.hasWindowFocus() == true
                            }
                            ready
                        }
                        if (rss)
                            compose
                                .onNodeWithTag("rss-import-code-1")
                                .performScrollTo()
                                .performClick()
                        else
                            compose
                                .onNodeWithTag("book-import-code-1")
                                .performScrollTo()
                                .performClick()
                        var preview: CodeDialog? = null
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                preview =
                                    parent!!
                                        .childFragmentManager
                                        .fragments
                                        .filterIsInstance<CodeDialog>()
                                        .firstOrNull()
                                ready =
                                    preview?.dialog?.window?.decorView?.hasWindowFocus() == true &&
                                        preview?.model?.state?.value?.loaded == true
                            }
                            ready
                        }
                        var original = ""
                        instrumentation.runOnMainSync {
                            assertEquals("1", preview!!.requestId)
                            original = preview!!.currentOriginalCode()
                            preview!!.setReplaceRuleRefreshPending(true)
                            assertFalse(!preview!!.model.state.value.busy)
                            preview!!.setReplaceRuleRefreshPending(false)
                            assertEquals(replacements, preview!!.model.state.value.showingAlternate)
                            assertTrue(
                                (!preview!!.model.state.value.showingAlternate ||
                                    preview!!.model.sourcePreview)
                            )
                        }
                        val editorLaunch = AtomicReference<Intent?>()
                        val launchMonitor = object : Instrumentation.ActivityMonitor() {
                            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                                if (intent.component?.className == CodeEditActivity::class.java.name) {
                                    editorLaunch.set(Intent(intent))
                                }
                                return null
                            }
                        }
                        var inputPath: String? = null
                        instrumentation.addMonitor(launchMonitor)
                        try {
                            compose.onNodeWithTag("code-fullscreen").performClick()
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    editorActivity =
                                        ActivityLifecycleMonitorRegistry.getInstance()
                                            .getActivitiesInStage(Stage.RESUMED)
                                            .filterIsInstance<CodeEditActivity>()
                                            .firstOrNull()
                                    val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                    ready =
                                        editor != null &&
                                            editor.text.toString() == original &&
                                            editor.isShown &&
                                            editor.isEditable
                                    if (ready) {
                                        inputPath = editorLaunch.get()?.getStringExtra("textFile")
                                        assertFalse(editorActivity!!.intent.hasExtra("textFile"))
                                        assertFalse(editorActivity!!.intent.hasExtra("text"))
                                        assertFalse(
                                            editorActivity!!.intent.getBooleanExtra("readOnly", false)
                                        )
                                    }
                                }
                                ready
                            }
                        } finally {
                            instrumentation.removeMonitor(launchMonitor)
                        }
                        assertNotNull("Long previews must use the actual transfer file", inputPath)
                        assertEquals(original, CodeTextTransfer.read(context, inputPath!!))
                        val insertion = original.indexOf(urls[1]) + urls[1].length
                        assertTrue(insertion > urls[1].length)
                        val edited =
                            original.substring(0, insertion) +
                                "#edited" +
                                original.substring(insertion)
                        instrumentation.runOnMainSync {
                            val editor = editorActivity!!.findViewById<CodeEditor>(R.id.editText)
                            editor.text.replace(insertion, insertion, "#edited")
                            val position =
                                editor.cursor.indexer.getCharPosition(insertion + "#edited".length)
                            editor.setSelection(position.line, position.column)
                            assertEquals(edited, editor.text.toString())
                            assertEquals(insertion + "#edited".length, editor.cursor.left)
                        }
                        val beforeRecreation = editorActivity
                        instrumentation.runOnMainSync { editorActivity!!.recreate() }
                        var recreationState = "No resumed editor"
                        await(message = { "Editor draft restoration: $recreationState" }) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                editorActivity =
                                    ActivityLifecycleMonitorRegistry.getInstance()
                                        .getActivitiesInStage(Stage.RESUMED)
                                        .filterIsInstance<CodeEditActivity>()
                                        .firstOrNull()
                                val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                recreationState =
                                    "newActivity=${editorActivity !== beforeRecreation}, " +
                                        "editable=${editor?.isEditable}, textMatches=${editor?.text?.toString() == edited}, " +
                                        "length=${editor?.text?.length}/${edited.length}, " +
                                        "cursor=${editor?.cursor?.left}/${insertion + "#edited".length}"
                                ready =
                                    editorActivity !== beforeRecreation &&
                                        editor != null &&
                                        editor.isEditable &&
                                        editor.text.toString() == edited &&
                                        editor.cursor.left == insertion + "#edited".length
                            }
                            ready
                        }
                        compose.onNodeWithTag("code-save").performClick()
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                val expected =
                                    if (replacements) edited.replace("#edited", "#edited-once")
                                    else edited
                                ready =
                                    preview!!.currentOriginalCode() == edited &&
                                        preview!!.model.state.value.displayed == expected &&
                                        preview!!.dialog?.window?.decorView?.hasWindowFocus() ==
                                            true &&
                                        !preview!!.model.state.value.busy
                                if (ready) {
                                    assertEquals(
                                        replacements,
                                        preview!!.model.state.value.showingAlternate,
                                    )
                                    assertTrue(
                                        (!preview!!.model.state.value.showingAlternate ||
                                            preview!!.model.sourcePreview)
                                    )
                                }
                            }
                            ready
                        }
                        assertFalse(
                            "The input transfer file must be removed after return",
                            File(inputPath!!).exists(),
                        )
                        compose.waitForIdle()
                        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                            try {
                                File(
                                        context.getExternalFilesDir("ui-regression"),
                                        "source-preview-editor-return-$rss-$replacements.png",
                                    )
                                    .outputStream()
                                    .use {
                                        assertTrue(
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                        )
                                    }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        // Discard a second edit: only the cursor may return, never the discarded
                        // draft.
                        compose.onNodeWithTag("code-fullscreen").performClick()
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                editorActivity =
                                    ActivityLifecycleMonitorRegistry.getInstance()
                                        .getActivitiesInStage(Stage.RESUMED)
                                        .filterIsInstance<CodeEditActivity>()
                                        .firstOrNull()
                                val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                ready = editor?.text?.toString() == edited && editor.isEditable
                            }
                            ready
                        }
                        instrumentation.runOnMainSync {
                            editorActivity!!
                                .findViewById<CodeEditor>(R.id.editText)
                                .text
                                .replace(insertion, insertion, "discarded")
                            editorActivity!!.finish()
                        }
                        compose.onNodeWithTag("code-discard-confirm").performClick()
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                ready =
                                    preview!!.dialog?.window?.decorView?.hasWindowFocus() == true &&
                                        !preview!!.model.state.value.busy
                                if (ready) assertEquals(edited, preview!!.currentOriginalCode())
                            }
                            ready
                        }
                        if (replacements) {
                            // A malformed draft stays editable; it must not overwrite the valid
                            // candidate.
                            val invalid = "] invalid source draft"
                            for (draft in listOf(invalid, edited)) {
                                compose.onNodeWithTag("code-fullscreen").performClick()
                                await {
                                    var ready = false
                                    instrumentation.runOnMainSync {
                                        editorActivity =
                                            ActivityLifecycleMonitorRegistry.getInstance()
                                                .getActivitiesInStage(Stage.RESUMED)
                                                .filterIsInstance<CodeEditActivity>()
                                                .firstOrNull()
                                        val editor =
                                            editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                        ready =
                                            editor != null &&
                                                editor.isEditable &&
                                                editor.hasWindowFocus()
                                    }
                                    ready
                                }
                                instrumentation.runOnMainSync {
                                    val editor =
                                        editorActivity!!.findViewById<CodeEditor>(R.id.editText)
                                    editor.text.replace(0, editor.text.length, draft)
                                }
                                compose.onNodeWithTag("code-save").performClick()
                                await {
                                    var ready = false
                                    instrumentation.runOnMainSync {
                                        ready =
                                            preview!!.currentOriginalCode() == draft &&
                                                preview!!
                                                    .dialog
                                                    ?.window
                                                    ?.decorView
                                                    ?.hasWindowFocus() == true &&
                                                !preview!!.model.state.value.busy
                                        if (ready) {
                                            val expected =
                                                if (draft == invalid) draft
                                                else edited.replace("#edited", "#edited-once")
                                            assertEquals(
                                                expected,
                                                preview!!.model.state.value.displayed,
                                            )
                                            val raw =
                                                if (rss)
                                                    ViewModelProvider(parent!!)[
                                                            RssImportViewModel::class.java]
                                                        .state
                                                        .value
                                                        .items[1]
                                                        .originalJson
                                                else
                                                    ViewModelProvider(parent!!)[
                                                            BookImportViewModel::class.java]
                                                        .state
                                                        .value
                                                        .items
                                                        .find { it.key == "1" }
                                                        ?.originalJson
                                            assertEquals(edited, raw)
                                        }
                                    }
                                    ready
                                }
                            }
                        }
                        compose.onNodeWithTag("code-save").performClick()
                        val expectedUrl = urls[1] + if (replacements) "#edited-once" else "#edited"
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                if (rss) {
                                    val actual =
                                        ViewModelProvider(parent!!)[RssImportViewModel::class.java]
                                            .state
                                            .value
                                            .items
                                            .map { GSON.fromJson(it.json, RssSource::class.java) }
                                    ready = actual.size == 2 && actual[1].sourceUrl == expectedUrl
                                    if (ready) {
                                        assertEquals(urls[0], actual[0].sourceUrl)
                                        assertEquals(script, actual[1].jsLib)
                                    }
                                } else {
                                    val actual =
                                        ViewModelProvider(parent!!)[BookImportViewModel::class.java]
                                            .state
                                            .value
                                            .items
                                            .map { GSON.fromJson(it.json, BookSource::class.java) }
                                    ready =
                                        actual.size == 2 && actual[1].bookSourceUrl == expectedUrl
                                    if (ready) {
                                        assertEquals(urls[0], actual[0].bookSourceUrl)
                                        assertEquals(script, actual[1].jsLib)
                                    }
                                }
                            }
                            ready
                        }
                        if (rss && !replacements) {
                            // Saving updates the candidate before the child dialog's removal
                            // transaction finishes. Wait for the parent to own input again.
                            await(message = { "Saved preview was not removed before reopening" }) {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    ready =
                                        !preview!!.isAdded &&
                                            parent!!.childFragmentManager.fragments.none {
                                                it === preview
                                            } &&
                                            parent!!.isResumed &&
                                            !parent!!.childFragmentManager.isStateSaved &&
                                            parent!!.dialog?.window?.decorView?.hasWindowFocus() == true
                                }
                                ready
                            }
                            val derived = edited.replace("#edited", "#replacement-only")
                            val readOnlyPreview =
                                CodeDialog(edited, false, "1", derived, showAlternate = true)
                            instrumentation.runOnMainSync {
                                readOnlyPreview.show(
                                    parent!!.childFragmentManager,
                                    "readonly-editor-entry",
                                )
                            }
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    ready =
                                        readOnlyPreview.dialog
                                            ?.window
                                            ?.decorView
                                            ?.hasWindowFocus() == true &&
                                            readOnlyPreview.model.state.value.loaded &&
                                            readOnlyPreview.isResumed &&
                                            !readOnlyPreview.parentFragmentManager.isStateSaved
                                }
                                ready
                            }
                            instrumentation.runOnMainSync {
                                assertTrue(readOnlyPreview.model.state.value.showingAlternate)
                                assertFalse(
                                    (!readOnlyPreview.model.state.value.showingAlternate ||
                                        readOnlyPreview.model.sourcePreview)
                                )
                            }
                            compose.onNodeWithTag("code-fullscreen").performClick()
                            await(message = {
                                var diagnostic = ""
                                instrumentation.runOnMainSync {
                                    val value = readOnlyPreview.model.state.value
                                    val activities = Stage.values().joinToString { stage ->
                                        val editors = ActivityLifecycleMonitorRegistry.getInstance()
                                            .getActivitiesInStage(stage)
                                            .filterIsInstance<CodeEditActivity>()
                                        "$stage=" + editors.joinToString { activity ->
                                            val editor = activity.findViewById<CodeEditor>(R.id.editText)
                                            "length=${editor?.text?.length}," +
                                                "matches=${editor?.text?.toString() == derived}," +
                                                "editable=${editor?.isEditable}," +
                                                "shown=${editor?.isShown}," +
                                                "finishing=${activity.isFinishing}"
                                        }
                                    }
                                    diagnostic =
                                        "Read-only preview editor not ready: " +
                                            "lifecycle=${readOnlyPreview.lifecycle.currentState}," +
                                            "added=${readOnlyPreview.isAdded}," +
                                            "stateSaved=${readOnlyPreview.parentFragmentManager.isStateSaved}," +
                                            "pending=${value.editorPending}," +
                                            "prepared=${value.editorPrepared}," +
                                            "hasPath=${value.editorPath != null}," +
                                            "readOnly=${value.editorReadOnly}," +
                                            "effects=${value.effects.map { it.action }}," +
                                            "hasError=${value.error != null}," +
                                            "expectedLength=${derived.length}; $activities"
                                }
                                diagnostic
                            }) {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    editorActivity =
                                        ActivityLifecycleMonitorRegistry.getInstance()
                                            .getActivitiesInStage(Stage.RESUMED)
                                            .filterIsInstance<CodeEditActivity>()
                                            .firstOrNull()
                                    val editor =
                                        editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                    ready =
                                        editor != null &&
                                            editor.text.toString() == derived &&
                                            !editor.isEditable
                                }
                                ready
                            }
                            instrumentation.runOnMainSync { editorActivity!!.finish() }
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    ready =
                                        readOnlyPreview.dialog
                                            ?.window
                                            ?.decorView
                                            ?.hasWindowFocus() == true &&
                                            readOnlyPreview.model.state.value.loaded
                                    if (ready)
                                        assertEquals(edited, readOnlyPreview.currentOriginalCode())
                                }
                                ready
                            }
                            instrumentation.runOnMainSync { readOnlyPreview.dismiss() }
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        editorActivity?.takeUnless { it.isFinishing }?.finish()
                    }
                    file.delete()
                }
            }
        } finally {
            AppConfig.importReplaceSource = savedReplacement
            appDb.replaceRuleDao.delete(rule)
        }
    }

    @Test
    fun fileBackedReplacementPreviewIsReadOnlyInTheFullEditor() {
        val code = "// replacement preview 中文 🌍\n".repeat(1_000)
        val path = CodeTextTransfer.write(context, code)
        try {
            scenario =
                ActivityScenario.launchActivityForResult(
                    Intent(context, CodeEditActivity::class.java).apply {
                        putExtra("textFile", path)
                        putExtra("useTextFile", true)
                        putExtra("readOnly", true)
                    }
                )
            awaitEditor { it.isShown && it.text.toString() == code }
            withEditor { assertFalse(it.isEditable) }
            scenario!!.onActivity { it.finish() }
            await { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_CANCELED, scenario!!.result.resultCode)
            assertEquals(code, CodeTextTransfer.read(context, path))
        } finally {
            CodeTextTransfer.delete(context, path)
        }
    }

    @Test
    fun safeEditorRetainsItsEditedDraftAndCursorAcrossRecreation() {
        val code = "\uFEFF// 中文 🌍\r\n" + "a" + "\u0301".repeat(12) + "\r\noriginal"
        val edited = code.replace("\r\n", "\n") + " 已修改"
        val cursor = edited.length - 2
        scenario =
            ActivityScenario.launchActivityForResult(
                Intent(context, CodeEditActivity::class.java).apply {
                    putExtra("text", code)
                    putExtra("useTextFile", true)
                }
            )
        await {
            var ready = false
            scenario!!.onActivity {
                ready =
                    ViewModelProvider(it)[CodeEditorComposeViewModel::class.java]
                        .state
                        .value
                        .session
                        ?.text == code &&
                        ViewModelProvider(it)[CodeEditorComposeViewModel::class.java]
                            .state.value.editorReady
            }
            ready
        }
        scenario!!.onActivity { activity ->
            nativeWebView(activity)
                .evaluateJavascript(
                    "editor.value = ${GSON.toJson(edited)}; editor.setSelectionRange($cursor, $cursor);" +
                        "editor.dispatchEvent(new Event('input'));",
                    null,
                )
        }
        await {
            var ready = false
            scenario!!.onActivity {
                ready =
                    ViewModelProvider(it)[CodeEditorComposeViewModel::class.java]
                        .state
                        .value
                        .session
                        ?.let { draft ->
                            draft.text == edited && draft.selection.start == cursor
                        } == true
            }
            ready
        }
        scenario!!.recreate()
        await {
            var ready = false
            scenario!!.onActivity {
                ready =
                    ViewModelProvider(it)[CodeEditorComposeViewModel::class.java]
                        .state
                        .value
                        .session
                        ?.text == edited &&
                        nativeWebViews(it.window.decorView).any { web -> web.isShown }
            }
            ready
        }
        val restored = AtomicReference<String>()
        await {
            scenario!!.onActivity {
                nativeWebView(it)
                    .evaluateJavascript(
                        "window.__getEditorState && window.__getEditorState();",
                        restored::set,
                    )
            }
            SafeEditorResultCodec.decode(restored.get())?.let {
                it.text == edited && it.cursorPosition == cursor && it.dirty
            } == true
        }
        compose.onNodeWithTag("code-save").performClick()
        await { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        val resultData = checkNotNull(scenario!!.result.resultData)
        val returnedFile = checkNotNull(resultData.getStringExtra("textFile"))
        try {
            assertFalse(resultData.hasExtra("text"))
            assertEquals(edited, CodeTextTransfer.read(context, returnedFile))
            assertEquals(cursor, resultData.getIntExtra("cursorPosition", -1))
        } finally {
            CodeTextTransfer.delete(context, returnedFile)
        }
    }

    @Test
    fun composePreviewTypesLongCodeWithSelectionAndActualIme() {
        launchEditor()
        val code = "{\"jsLib\":\"" + "var value = '中文'; ".repeat(12_000) + "\"}"
        val dialog = CodeDialog(code, disableEdit = false)
        val timings = linkedMapOf<String, Long>()
        scenario!!.onActivity { dialog.show(it.supportFragmentManager, "compose-input-cost") }
        try {
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = dialog.model.state.value.loaded }
                ready
            }
            val focusStart = SystemClock.uptimeMillis()
            compose.onNodeWithTag("code-body").performClick()
            await {
                var visible = false
                instrumentation.runOnMainSync {
                    visible =
                        ViewCompat.getRootWindowInsets(dialog.requireView())
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            timings["focusAndImeMs"] = SystemClock.uptimeMillis() - focusStart
            val offset = code.length - 2
            compose
                .onNodeWithTag("code-body")
                .performTextInputSelection(androidx.compose.ui.text.TextRange(offset))
            val inputStart = SystemClock.uptimeMillis()
            compose.onNodeWithTag("code-body").performTextInput("中")
            compose.waitForIdle()
            timings["inputAndFrameMs"] = SystemClock.uptimeMillis() - inputStart
            val expected = code.substring(0, offset) + "中" + code.substring(offset)
            instrumentation.runOnMainSync {
                assertEquals(expected, dialog.currentOriginalCode())
                assertEquals(offset + 1, dialog.model.state.value.selectionEnd)
            }
            val artifacts = checkNotNull(context.getExternalFilesDir("ui-regression"))
            File(artifacts, "compose-preview-input.txt")
                .writeText("characters=${code.length}\n$timings\n")
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                try {
                    File(artifacts, "compose-preview-input.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            assertTrue(
                "Typing stalls the code preview: $timings",
                timings.getValue("inputAndFrameMs") < 1_000,
            )
            compose.onNodeWithTag("code-body").performTextReplacement("prefix target")
            compose.onNodeWithTag("code-body").assertTextEquals("prefix target")
        } finally {
            instrumentation.runOnMainSync { dialog.dismissAllowingStateLoss() }
        }
    }

    @Test
    fun composePreviewReportsActualAccessibilityPayloadDuringRepeatedDeletion() {
        launchEditor()
        val accessibility = io.legado.app.ci.AccessibilityServiceSession(instrumentation)
        var expected = "{\"jsLib\":\"" + "var value = '中文'; ".repeat(4_500) + "\"}"
        val dialog = CodeDialog(expected, disableEdit = false)
        val report = StringBuilder("characters=${expected.length}\n")
        val artifacts = checkNotNull(context.getExternalFilesDir("ui-regression"))
        try {
            await {
                context.getSystemService(AccessibilityManager::class.java)
                    .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { android.content.ComponentName.unflattenFromString(it.id) == accessibility.component }
            }
            scenario!!.onActivity {
                dialog.show(it.supportFragmentManager, "compose-accessibility-cost")
            }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = dialog.model.state.value.loaded }
                ready
            }
            compose.onNodeWithTag("code-body").performClick()
            await {
                var visible = false
                instrumentation.runOnMainSync {
                    visible =
                        ViewCompat.getRootWindowInsets(dialog.requireView())
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            assertTrue(context.getSystemService(AccessibilityManager::class.java).isEnabled)
            repeat(4) { iteration ->
                val before = expected
                val cursor = before.length - 2
                compose
                    .onNodeWithTag("code-body")
                    .performTextInputSelection(androidx.compose.ui.text.TextRange(cursor))
                compose.onNodeWithTag("code-body").assertIsFocused()
                var selectionDescription = ""
                await(message = { "Preview selection did not reach $cursor: $selectionDescription" }) {
                    var selected = false
                    instrumentation.runOnMainSync {
                        val current = dialog.model.state.value
                        selectionDescription = "selection=${current.selectionStart}/${current.selectionEnd}; length=${current.displayed.length}; windowFocus=${dialog.requireDialog().window?.decorView?.hasWindowFocus()}"
                        selected = dialog.model.state.value.selectionStart == cursor &&
                            dialog.model.state.value.selectionEnd == cursor &&
                            dialog.requireDialog().window?.decorView?.hasWindowFocus() == true
                    }
                    selected
                }
                val started = SystemClock.uptimeMillis()
                val event =
                    accessibility.automation.executeAndWaitForEvent(
                        {
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL)
                            await {
                                var changed = false
                                instrumentation.runOnMainSync {
                                    changed = dialog.currentOriginalCode() == before.removeRange(cursor - 1, cursor)
                                }
                                changed
                            }
                            compose.waitForIdle()
                        },
                        { received ->
                            received.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                                received.packageName?.toString() == context.packageName &&
                                received.fromIndex == cursor - 1 &&
                                received.removedCount == 1 &&
                                received.addedCount == 0
                        },
                        10_000,
                    )
                try {
                    expected = before.removeRange(cursor - 1, cursor)
                    val parcel = Parcel.obtain()
                    val bytes =
                        try {
                            event.writeToParcel(parcel, 0)
                            parcel.dataSize()
                        } finally {
                            parcel.recycle()
                        }
                    report.append(
                        "delete=$iteration; parcelBytes=$bytes; beforeChars=${event.beforeText?.length}; textChars=${event.text.firstOrNull()?.length}; frameMs=${SystemClock.uptimeMillis() - started}\n"
                    )
                    assertEquals(before, event.beforeText?.toString())
                    assertEquals(expected, event.text.single().toString())
                    compose.waitForIdle()
                    instrumentation.runOnMainSync {
                        assertEquals(expected, dialog.currentOriginalCode())
                        assertEquals(cursor - 1, dialog.model.state.value.selectionStart)
                        assertEquals(cursor - 1, dialog.model.state.value.selectionEnd)
                    }
                } finally {
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        // Accessibility event pooling exists only before Android 13.
                        @Suppress("DEPRECATION")
                        event.recycle()
                    }
                }
            }
        } finally {
            accessibility.close()
            File(artifacts, "compose-preview-accessibility.txt").writeText(report.toString())
            instrumentation.runOnMainSync { dialog.dismissAllowingStateLoss() }
        }
    }

    @Test
    fun reportedRssPreviewMeasuresOpeningImeAndEditsWithItsActualIcon() {
        val connection =
            java.net
                .URL("https://github.com/user-attachments/files/32066159/shareRssSource.json")
                .openConnection()
                .apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
        val bytes = connection.getInputStream().use { it.readBytes() }
        val digest =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        assertEquals("0893a7bf2af946735d331d1e37acdf3aa3bf05f3b5beb45181f736e15509f9a9", digest)
        val rss =
            GSON.fromJson(bytes.toString(Charsets.UTF_8), Array<RssSource>::class.java).single()
        val actual = GSON.toJson(rss)
        val withoutIcon = GSON.toJson(rss.copy(sourceIcon = ""))
        launchEditor()
        val artifacts = checkNotNull(context.getExternalFilesDir("ui-regression"))
        val report = StringBuilder("sampleSha256=$digest\n")
        try {
            for ((label, code) in listOf("actual" to actual, "without-icon" to withoutIcon)) {
                val dialog = CodeDialog(code, disableEdit = false)
                var expected = code
                val opened = SystemClock.uptimeMillis()
                scenario!!.onActivity {
                    dialog.show(it.supportFragmentManager, "reported-rss-$label")
                }
                try {
                    await {
                        var ready = false
                        instrumentation.runOnMainSync {
                            ready =
                                dialog.model.state.value.loaded &&
                                    dialog.dialog?.window?.decorView?.hasWindowFocus() == true
                        }
                        ready
                    }
                    report.append(
                        "$label; characters=${code.length}; openMs=${SystemClock.uptimeMillis() - opened}\n"
                    )
                    val focusStarted = SystemClock.uptimeMillis()
                    compose.onNodeWithTag("code-body").performClick()
                    await {
                        var visible = false
                        instrumentation.runOnMainSync {
                            visible =
                                ViewCompat.getRootWindowInsets(dialog.requireView())
                                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        }
                        visible
                    }
                    report.append(
                        "$label; focusImeMs=${SystemClock.uptimeMillis() - focusStarted}\n"
                    )
                    for (offset in listOf(15, code.length / 2, code.length - 3)) {
                        compose
                            .onNodeWithTag("code-body")
                            .performTextInputSelection(androidx.compose.ui.text.TextRange(offset))
                        val started = SystemClock.uptimeMillis()
                        compose.onNodeWithTag("code-body").performTextInput("x")
                        expected = expected.substring(0, offset) + "x" + expected.substring(offset)
                        compose.waitForIdle()
                        instrumentation.runOnMainSync {
                            assertEquals(expected, dialog.currentOriginalCode())
                            assertEquals(offset + 1, dialog.model.state.value.selectionEnd)
                        }
                        report.append(
                            "$label; offset=$offset; inputFrameMs=${SystemClock.uptimeMillis() - started}\n"
                        )
                        val deleteStarted = SystemClock.uptimeMillis()
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL)
                        expected = expected.removeRange(offset, offset + 1)
                        await {
                            var matches = false
                            instrumentation.runOnMainSync {
                                matches = dialog.currentOriginalCode() == expected
                            }
                            matches
                        }
                        report.append(
                            "$label; offset=$offset; keyDeleteMs=${SystemClock.uptimeMillis() - deleteStarted}\n"
                        )
                    }
                    checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                        try {
                            File(artifacts, "reported-rss-$label.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } finally {
                    closeSoftKeyboard()
                    instrumentation.runOnMainSync { dialog.dismissAllowingStateLoss() }
                    compose.waitForIdle()
                }
            }
        } finally {
            File(artifacts, "reported-rss-preview.txt").writeText(report.toString())
        }
    }

    private fun launchEditor(
        readOnly: Boolean = false,
        forResult: Boolean = false,
        fileMode: Boolean = false,
    ) {
        val intent =
            Intent(context, CodeEditActivity::class.java)
                .putExtra("title", "Code selection regression")
                .putExtra("useTextFile", fileMode)
        if (readOnly) {
            CacheManager.putMemory(cacheKey, source)
            intent.putExtra("cacheKey", cacheKey)
        } else {
            intent.putExtra("text", source)
        }
        scenario =
            if (forResult) ActivityScenario.launchActivityForResult(intent)
            else ActivityScenario.launch(intent)
        awaitEditor { it.isShown && it.width > 0 && it.text.toString() == source && it.hasFocus() }
        closeSoftKeyboard()
        val actionBounds = Rect()
        composeScreenBounds("code-search-toggle", actionBounds)
        scenario!!.onActivity { activity ->
            val safeTop = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            assertTrue("Editor toolbar overlaps the system status bar", actionBounds.top >= safeTop)
            assertTrue("Editor toolbar adds excess top spacing", actionBounds.top <= safeTop + activity.resources.displayMetrics.density * 8)
        }
    }

    private fun selectFunction(native: Boolean = false) {
        withEditor { it.setSelectionRegion(1, 0, 4, 1, false) }
        awaitEditor {
            selection(it) == selectedText && actions(it).isEnabled && actions(it).isShowing
        }
        assertEditorToolbar()
        if (native) {
            dismissEditorToolbar()
            press(Tap.LONG, 2, 11)
            assertNativeMenu()
        }
    }

    private fun press(tap: Tap, line: Int, column: Int) {
        onView(withId(R.id.editText))
            .perform(
                GeneralClickAction(
                    tap,
                    { view ->
                        val editor = view as CodeEditor
                        val location = IntArray(2)
                        editor.getLocationOnScreen(location)
                        val point =
                            floatArrayOf(
                                location[0] + editor.getCharOffsetX(line, column) + editor.dpUnit,
                                location[1] + editor.getCharOffsetY(line, column) -
                                    editor.rowHeight / 2f,
                            )
                        val visible = Rect()
                        assertTrue(editor.getGlobalVisibleRect(visible))
                        assertTrue(
                            "Gesture must hit the visible editor at line $line, column $column",
                            visible.contains(point[0].toInt(), point[1].toInt()),
                        )
                        point
                    },
                    Press.FINGER,
                    0,
                    0,
                )
            )
    }

    private fun editorPoint(line: Int, column: Int): FloatArray {
        var point = floatArrayOf()
        withEditor {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            point =
                floatArrayOf(
                    location[0] + it.getCharOffsetX(line, column) + it.dpUnit,
                    location[1] + it.getCharOffsetY(line, column) - it.rowHeight / 2f,
                )
        }
        return point
    }

    @Suppress("DEPRECATION")
    private fun shareAndAssert(expected: String) {
        val chooser = AtomicReference<Intent?>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_CHOOSER) return null
                    chooser.set(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            var native = false
            withEditor { native = !actions(it).isEnabled }
            if (native) {
                clickNativeAction(R.string.share)
            } else {
                onView(withId(R.id.code_share_selection)).inRoot(isPlatformPopup()).perform(click())
            }
            await { chooser.get() != null }
            val send = chooser.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
            assertNotEquals(source, send.getStringExtra(Intent.EXTRA_TEXT))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun actions(editor: CodeEditor) =
        editor.getComponent(EditorTextActionWindow::class.java)

    private fun dismissEditorToolbar() {
        lateinit var popup: View
        withEditor {
            popup = actions(it).view.rootView
            actions(it).dismiss()
        }
        await { !popup.isAttachedToWindow }
        // Popup removal reaches InputDispatcher asynchronously; do not inject into its stale
        // window.
        // Accessibility idle alone does not synchronize the removed input window.
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
    }

    private fun assertNativeMenu() {
        // Sora's image-button popup has no text labels. This checks the actual platform popup.
        onView(withText(android.R.string.copy))
            .inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
        withEditor {
            assertFalse("The editor toolbar must not overlap Android's menu", actions(it).isShowing)
            assertFalse(actions(it).isEnabled)
        }
    }

    private fun assertEditorToolbar() {
        onView(withId(R.id.code_share_selection))
            .inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
        withEditor {
            assertTrue(actions(it).isEnabled)
            assertTrue(actions(it).isShowing)
        }
        onView(withText(android.R.string.copy)).check(doesNotExist())
    }

    private fun assertNativeMenuClearOfTools() {
        val menuBounds = Rect()
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check { view, failure ->
            if (failure != null) throw failure
            val nativeTextId =
                context.resources.getIdentifier("floating_toolbar_menu_item_text", "id", "android")
            assertEquals(
                "The selection action must be rendered by Android",
                nativeTextId,
                view!!.id,
            )
            val panel = view.parent.parent as View
            visibleScreenBounds(panel, menuBounds)
        }
        val toolBounds = Rect()
        composeScreenBounds("code-keyboard", toolBounds)
        assertFalse(
            "Native menu $menuBounds overlaps keyboard tools $toolBounds",
            Rect.intersects(menuBounds, toolBounds),
        )
        val keyboardBounds = Rect(toolBounds)
        composeScreenBounds("code-search", toolBounds)
        assertFalse(
            "Native menu $menuBounds overlaps search tools $toolBounds",
            Rect.intersects(menuBounds, toolBounds),
        )
        File(context.getExternalFilesDir("ui-regression"), "code-selection-native-menu-bounds.txt")
            .appendText("menu=$menuBounds; keyboard=$keyboardBounds; search=$toolBounds\n")
    }

    private fun visibleScreenBounds(view: View, bounds: Rect) {
        // getGlobalVisibleRect is relative to this window's root, even for a PopupWindow.
        assertTrue(view.getGlobalVisibleRect(bounds))
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        bounds.offset(origin[0], origin[1])
    }

    private fun dismissNativeMenu() {
        var native = false
        withEditor { native = !actions(it).isEnabled }
        // Exercise the real Back callback for native mode; Sora's nonmodal toolbar has no Back
        // action.
        if (native) pressBack() else withEditor { actions(it).dismiss() }
        withEditor { assertTrue(it.isShown) }
    }

    private fun clickNativeAction(label: Int) {
        // Android keeps the overflow panel in the view tree while it is invisible.
        val visibleAction = allOf(withText(label), isDisplayed())
        try {
            onView(visibleAction).inRoot(isPlatformPopup()).check(matches(isCompletelyDisplayed()))
        } catch (_: NoMatchingViewException) {
            val overflow =
                context.resources.getIdentifier(
                    "floating_toolbar_open_overflow_description",
                    "string",
                    "android",
                )
            assertNotEquals("Android's overflow accessibility label must exist", 0, overflow)
            onView(withContentDescription(context.getString(overflow)))
                .inRoot(isPlatformPopup())
                .perform(click())
        }
        onView(visibleAction)
            .inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
            .perform(click())
    }

    private fun selection(editor: CodeEditor) =
        editor.text.subSequence(editor.cursor.left, editor.cursor.right).toString()

    private fun withEditor(action: (CodeEditor) -> Unit) {
        scenario!!.onActivity { action(it.findViewById(R.id.editText)) }
    }

    private fun awaitEditor(condition: (CodeEditor) -> Boolean) {
        var diagnostic = "native editor missing"
        await(message = { "Code editor did not reach the expected state: $diagnostic" }) {
            var ready = false
            scenario!!.onActivity { activity ->
                activity.findViewById<CodeEditor>(R.id.editText)?.let {
                    diagnostic = "shown=${it.isShown}, size=${it.width}x${it.height}, focus=${it.hasFocus()}, windowFocus=${it.hasWindowFocus()}, editable=${it.isEditable}, length=${it.text.length}"
                    ready = condition(it)
                }
            }
            ready
        }
    }

    private fun nativeWebViews(view: View): List<WebView> =
        when (view) {
            is WebView -> listOf(view)
            is ViewGroup -> view.children.flatMap { nativeWebViews(it).asSequence() }.toList()
            else -> emptyList()
        }

    private fun nativeWebView(activity: CodeEditActivity): WebView =
        nativeWebViews(activity.window.decorView).single()

    private fun composeScreenBounds(tag: String, bounds: Rect) {
        val rootBounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        scenario!!.onActivity { activity ->
            val composeView =
                nativeViews(activity.window.decorView).first {
                    it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView"
                }
            val origin = IntArray(2)
            composeView.getLocationOnScreen(origin)
            bounds.set(
                rootBounds.left.toInt() + origin[0],
                rootBounds.top.toInt() + origin[1],
                rootBounds.right.toInt() + origin[0],
                rootBounds.bottom.toInt() + origin[1],
            )
        }
    }

    private fun nativeViews(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) view.children.flatMap { nativeViews(it).asSequence() }.toList()
            else emptyList()

    private fun await(
        message: () -> String = { "Code editor did not reach the expected state" },
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            assertTrue(message(), condition())
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // FloatingActionMode briefly hides the toolbar while the selection geometry moves.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val committed = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val root = activity.window.decorView
            assertTrue(root.isHardwareAccelerated)
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("Editor frame was not committed", committed.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
