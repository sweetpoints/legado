package io.legado.app.ui.replace.edit

import android.app.Activity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.ui.code.CodeEditActivity
import java.util.concurrent.atomic.AtomicReference
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.flutter.embedding.android.FlutterFragment
import java.util.ArrayDeque
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceEditorField
import io.legado.app.data.repository.ReplaceEditorText
import io.legado.app.help.config.ReplacePreviewConfig
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReplaceEditorHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = FlutterUiTestDriver(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ReplaceEditActivity>? = null
    private lateinit var model: ReplaceEditorViewModel
    private val ids = mutableListOf<Long>()
    private val sessions = mutableListOf<String>()

    @After
    fun cleanup() {
        scenario?.close()
        driver.close()
        runBlocking(Dispatchers.IO) {
            ids.forEach { id ->
                appDb.replaceRuleDao.findById(id)?.let { appDb.replaceRuleDao.delete(it) }
                ReplacePreviewConfig.removeSample(id)
            }
        }
        sessions.forEach { File(context.filesDir, "replace-editor-drafts/$it.json").delete() }
    }

    private fun launch(rule: ReplaceRule? = null) {
        rule?.let {
            ids += it.id
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(it) }
        }
        driver.enableAccessibility()
        scenario =
            ActivityScenario.launchActivityForResult(
                ReplaceEditActivity.startIntent(
                    context,
                    rule?.id ?: -1,
                    pattern = if (rule == null) "a" else null,
                    isRegex = false,
                    scope = if (rule == null) "Book" else null,
                )
            )
        scenario!!.onActivity {
            model = it.viewModel
            sessions += model.session
        }
        driver.await("native replacement loaded") { model.state.value.loaded }
        scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment }) }
        driver.awaitIdentifier("replace-editor-Name")
    }

    private fun reveal(id: String, forInput: Boolean = false) {
        if (forInput) androidx.test.espresso.Espresso.closeSoftKeyboard()
        var previousGeometry: Pair<Double, Double>? = null
        driver.await("rendered field $id") {
            if (forInput) {
                val actual = renderedState() ?: return@await false
                val geometry = (actual["viewportDimension"] as? Number)?.toDouble() to
                    (actual["scrollOffset"] as? Number)?.toDouble()
                val stable = geometry.first != null && geometry.second != null &&
                    previousGeometry == (geometry.first!! to geometry.second!!)
                if (geometry.first != null && geometry.second != null) previousGeometry = geometry.first!! to geometry.second!!
                if ((actual["viewInsetsBottom"] as? Number)?.toDouble() != 0.0 || actual["restoring"] != false || !stable) return@await false
            }
            driver.node(id)?.let { target ->
                var ready = !forInput
                if (forInput) {
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    pending.add(AccessibilityNodeInfo.obtain(target))
                    val viewport = driver.node("replace-editor-fields")
                    val area = viewport?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
                    viewport?.recycle()
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        if (node.isVisibleToUser && node.isEnabled && node.isClickable && area?.contains(bounds) == true &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) ready = true
                        for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
                        node.recycle()
                    }
                }
                target.recycle()
                if (ready) return@await true
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            var scrolled = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!scrolled && node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrolled = true
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                node.recycle()
            }
            if (scrolled) SystemClock.sleep(150)
            false
        }
    }

    private var renderedDiagnostic = "not requested"

    /** Native-to-Dart read only: observe actual controllers/viewport, never mutate the page. */
    private fun renderedState(): Map<*, *>? {
        val read = AtomicReference<Map<*, *>?>()
        val completed = java.util.concurrent.CountDownLatch(1)
        var requested = false
        scenario!!.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<FlutterFragment>().singleOrNull()
            val engine = fragment?.flutterEngine
            if (engine != null) {
                requested = true
                val generation = activity.editorCoordinator.generation
                io.flutter.plugin.common.MethodChannel(engine.dartExecutor.binaryMessenger, "legado/replace_editor")
                    .invokeMethod("renderedState", null, object : io.flutter.plugin.common.MethodChannel.Result {
                        override fun success(result: Any?) {
                            val value = result as? Map<*, *>
                            renderedDiagnostic = "generation=${value?.get("generation")}/$generation"
                            if (value?.get("generation") == generation) read.set(value)
                            completed.countDown()
                        }
                        override fun error(code: String, message: String?, details: Any?) {
                            renderedDiagnostic = "error=$code"
                            completed.countDown()
                        }
                        override fun notImplemented() {
                            renderedDiagnostic = "not implemented"
                            completed.countDown()
                        }
                    })
            }
        }
        if (!requested) renderedDiagnostic = "engine not ready"
        else if (!completed.await(2, java.util.concurrent.TimeUnit.SECONDS)) renderedDiagnostic = "read timeout"
        return read.get()
    }

    private fun awaitSampleInputFrame() {
        // A newly focused offscreen form field can still own an IME/layout transition.
        // Stabilize that transition before the single ACTION_SET_TEXT; never repeat the input.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val deadline = SystemClock.uptimeMillis() + 30_000
        fun ready(): android.graphics.Rect? {
            var nativeReady = false
            scenario!!.onActivity {
                nativeReady = model.state.value.focus == ReplaceEditorField.Sample &&
                    androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
            }
            if (!nativeReady) return null
            val node = driver.node("replace-editor-Sample") ?: return null
            return try {
                if (node.isFocused && node.isEditable && node.isEnabled && node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                }) android.graphics.Rect().also(node::getBoundsInScreen) else null
            } finally { node.recycle() }
        }
        driver.await("Sample native focus, hidden IME and actual editable frame") {
            val before = ready() ?: return@await false
            val frame = java.util.concurrent.CountDownLatch(1)
            val callback = Runnable { frame.countDown() }
            scenario!!.onActivity {
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback(callback)
                it.window.decorView.postInvalidateOnAnimation()
            }
            try {
                val remaining = deadline - SystemClock.uptimeMillis()
                remaining > 0 && frame.await(minOf(remaining, 2_000), java.util.concurrent.TimeUnit.MILLISECONDS) &&
                    before == ready()
            } finally {
                scenario!!.onActivity { it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(callback) }
            }
        }
    }

    @Test
    fun savedRulePreservesHiddenMetadataAndReturnsSuccessOnlyAfterRoomReceipt() {
        val rule =
            ReplaceRule(
                id = System.currentTimeMillis(),
                name = "Original",
                pattern = "a",
                replacement = "b",
                isRegex = false,
                isEnabled = false,
                order = 42,
                scopeTitle = true,
                scopeSource = true,
                scopeContent = false,
            )
        launch(rule)
        driver.setText("replace-editor-Name", " Edited ")
        driver.await("name ACK") { model.state.value.draft[ReplaceEditorField.Name].text == " Edited " }
        reveal("replace-editor-Sample", forInput = true)
        driver.setText("replace-editor-Sample", "aaa", afterFocus = ::awaitSampleInputFrame)
        driver.await("sample ACK") { model.state.value.draft[ReplaceEditorField.Sample].text == "aaa" }
        driver.await("native literal preview") { model.state.value.preview == "bbb" }
        reveal("replace-editor-output-text")
        driver.await("rendered literal preview") { driver.text("replace-editor-output-text") == "bbb" }
        driver.click("replace-editor-save")
        driver.await("Room receipt completed") { model.state.value.finished && model.state.value.saved }
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        val saved = runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!! }
        assertEquals(" Edited ", saved.name)
        assertFalse(saved.isEnabled)
        assertEquals(42, saved.order)
        assertTrue(saved.scopeTitle)
        assertTrue(saved.scopeSource)
        assertFalse(saved.scopeContent)
        assertEquals("aaa", ReplacePreviewConfig.sample(rule.id))
    }

    @Test
    fun fullscreenUsesNativeTransferAndReturnsToFlutterBeforeRoomSave() {
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Original", pattern = "a", replacement = "b")
        launch(rule)
        driver.setText("replace-editor-Name", "Draft")
        driver.await("name field ACK before fullscreen") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        driver.click("replace-editor-fullscreen")
        val native = AtomicReference<CodeEditActivity>()
        try {
            driver.await("native fullscreen text transfer") {
                var ready = false
                instrumentation.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<CodeEditActivity>().singleOrNull()
                    val editor = activity?.findViewById<CodeEditor>(R.id.editText)
                    ready = editor?.isShown == true && editor.isEditable && editor.text.toString() == "Draft"
                    if (ready) native.set(activity)
                }
                ready
            }
            instrumentation.runOnMainSync {
                val editor = native.get().findViewById<CodeEditor>(R.id.editText)
                editor.text.replace(0, editor.text.length, "Fullscreen returned")
            }
            driver.click("code-save")
            driver.await("returned actual Flutter draft") { driver.text("replace-editor-Name") == "Fullscreen returned" }
            assertEquals("Original", runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name })
            driver.click("replace-editor-save")
            driver.await("final Room save") { model.state.value.finished && model.state.value.saved }
            assertEquals("Fullscreen returned", runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name })
        } finally {
            instrumentation.runOnMainSync { native.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.finish() }
        }
    }

    @Test
    fun recreationRestoresRawSelectionAndScrollWithoutOverwritingDraftFromRoom() {
        launch()
        val draft = "Long name\n".repeat(100)
        driver.setText("replace-editor-Name", draft)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        driver.await("initial text ACK and hidden IME settle before setting the reverse selection") {
            val actual = renderedState() ?: return@await false
            val name = (actual["fields"] as? Map<*, *>)?.get("Name") as? Map<*, *> ?: return@await false
            val native = model.state.value.draft[ReplaceEditorField.Name]
            name["text"] == draft && native.text == draft && name["focused"] == true &&
                name["start"] == native.start && name["end"] == native.end &&
                actual["restoring"] == false && (actual["viewInsetsBottom"] as? Number)?.toDouble() == 0.0
        }
        val field = requireNotNull(driver.node("replace-editor-Name"))
        try {
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 27)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 4)
            }))
        } finally { field.recycle() }
        var checkpointDiagnostic = "not read"
        var previousAcknowledgedViewport: Double? = null
        try {
            driver.await("real draft and reverse selection acknowledged before checkpoint") {
                val actual = renderedState()
                if (actual == null) { checkpointDiagnostic = renderedDiagnostic; return@await false }
                val name = (actual["fields"] as? Map<*, *>)?.get("Name") as? Map<*, *>
                val native = model.state.value.draft[ReplaceEditorField.Name]
                checkpointDiagnostic = "renderedLength=${(name?.get("text") as? String)?.length}, " +
                    "range=${name?.get("start")}..${name?.get("end")}, focused=${name?.get("focused")}, " +
                    "restoring=${actual["restoring"]}, inset=${actual["viewInsetsBottom"]}, " +
                    "offset=${actual["scrollOffset"]}, nativeLength=${native.text.length}, " +
                    "nativeRange=${native.start}..${native.end}"
                // Setting a selection can schedule caret-reveal scrolling after its field ACK.
                // Do not seed a saved viewport while that older rendered offset is still in flight.
                val viewport = (actual["scrollOffset"] as? Number)?.toDouble()
                val viewportAcknowledged = viewport != null && kotlin.math.abs(viewport - model.state.value.scroll) < 0.5
                val stableViewport = viewportAcknowledged && previousAcknowledgedViewport == viewport
                previousAcknowledgedViewport = viewport.takeIf { viewportAcknowledged }
                name != null && name["text"] == draft && name["start"] == 27 && name["end"] == 4 && name["focused"] == true &&
                    actual["restoring"] == false && (actual["viewInsetsBottom"] as? Number)?.toDouble() == 0.0 &&
                    native == ReplaceEditorText(draft, 27, 4) && stableViewport
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; $checkpointDiagnostic", failure)
        }
        // Seed the original saved viewport contract only after field/IME work has settled.
        // The next host must restore this exact offset in the actual Flutter viewport.
        fun nativeDiagnostic(): String {
            val state = model.state.value
            val value = state.draft[ReplaceEditorField.Name]
            return "vm=${System.identityHashCode(model)}, session=${model.session}, scroll=${state.scroll}, " +
                "focus=${state.focus}, length=${value.text.length}, range=${value.start}..${value.end}, " +
                "loaded=${state.loaded}, busy=${state.busy}, editor=${state.editor != null}"
        }
        var beforeRecreate = ""
        var afterRecreate = ""
        scenario!!.onActivity {
            model.scroll(500)
            runBlocking { model.flushDraft() }
            assertEquals(500, model.state.value.scroll)
            beforeRecreate = "generation=${it.editorCoordinator.generation}, ${nativeDiagnostic()}"
        }
        scenario!!.recreate()
        scenario!!.onActivity {
            model = it.viewModel
            afterRecreate = "generation=${it.editorCoordinator.generation}, ${nativeDiagnostic()}"
        }
        driver.await("native replacement loaded") { model.state.value.loaded }
        scenario!!.onActivity { assertTrue(it.supportFragmentManager.fragments.any { fragment -> fragment is FlutterFragment }) }
        driver.awaitIdentifier("replace-editor-fields")
        var restoredDiagnostic = "not read"
        try {
            driver.await("actual restored Flutter viewport, focus and reverse selection") {
                val actual = renderedState()
                val name = (actual?.get("fields") as? Map<*, *>)?.get("Name") as? Map<*, *>
                restoredDiagnostic = "$renderedDiagnostic; actual=" + actual?.filterKeys { it != "fields" } +
                    "; nameLength=${(name?.get("text") as? String)?.length}, range=${name?.get("start")}..${name?.get("end")}, " +
                    "focused=${name?.get("focused")}; ${nativeDiagnostic()}"
                actual != null && name != null && actual["restoring"] == false &&
                    kotlin.math.abs(((actual["scrollOffset"] as? Number)?.toDouble() ?: -1.0) - 500.0) < 0.5 &&
                    name["text"] == draft && name["start"] == 27 && name["end"] == 4 && name["focused"] == true &&
                    model.state.value.scroll == 500
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; before=[$beforeRecreate]; after=[$afterRecreate]; $restoredDiagnostic", failure)
        }
        assertEquals(draft, model.state.value.draft[ReplaceEditorField.Name].text)
        assertEquals(27, model.state.value.draft[ReplaceEditorField.Name].start)
        assertEquals(4, model.state.value.draft[ReplaceEditorField.Name].end)
        assertEquals("Book", model.state.value.draft[ReplaceEditorField.Scope].text)
        assertEquals(500, model.state.value.scroll)
    }

    @Test
    fun actualBackShowsDirtyConfirmationAndNoDiscardsWithoutDatabaseWrite() {
        val rule =
            ReplaceRule(
                id = System.currentTimeMillis(),
                name = "Original",
                pattern = "a",
                isRegex = false,
                order = 9,
            )
        launch(rule)
        driver.setText("replace-editor-Name", "Draft")
        driver.await("draft ACK") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        driver.click("replace-editor-keep")
        assertFalse(model.state.value.finished)
        driver.await("draft ACK") { model.state.value.draft[ReplaceEditorField.Name].text == "Draft" }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        driver.click("replace-editor-discard")
        driver.await("discard finished") { model.state.value.finished }
        assertFalse(model.state.value.saved)
        assertEquals(
            "Original",
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name },
        )
    }
}
