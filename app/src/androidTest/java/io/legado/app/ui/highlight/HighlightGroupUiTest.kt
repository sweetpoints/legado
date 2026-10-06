package io.legado.app.ui.highlight

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.inspector.WindowInspector
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.HighlightStyle
import io.legado.app.help.IntentData
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HighlightGroupUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dao = appDb.highlightRuleDao
    private val namedUngrouped = context.getString(R.string.no_group)
    private var savedRules = emptyList<HighlightRule>()
    private var scenario: ActivityScenario<HighlightRuleActivity>? = null
    private val fixtures =
        listOf(
            HighlightRule(name = "Alice", pattern = "Alice", group = "Characters", order = 0)
                .apply {
                    applyStyle(
                        HighlightStyle(
                            fill = 0xFF209050.toInt(),
                            fillShape = HighlightStyle.FillShape.PILL,
                            pillPaddingScale = 1.25f,
                        )
                    )
                },
            HighlightRule(name = "Bob", pattern = "Bob", group = "Characters", order = 1),
            HighlightRule(name = "Quote", pattern = "Quote", group = "Quotes", order = 2),
            HighlightRule(
                name = "Named group",
                pattern = "Named",
                group = namedUngrouped,
                order = 3,
            ),
            HighlightRule(name = "Loose rule", pattern = "Loose", order = 4),
        )

    @Before
    fun setUp() {
        savedRules = dao.all
        dao.deleteAll()
        dao.insert(*fixtures.toTypedArray())
        scenario = ActivityScenario.launch(HighlightRuleActivity::class.java)
        awaitRules(dao.all)
    }

    @After
    fun cleanUp() {
        scenario?.close()
        dao.deleteAll()
        if (savedRules.isNotEmpty()) dao.insert(*savedRules.toTypedArray())
    }

    @Test
    fun fontSizeAndNegativeSpacingPersistAndResetThroughTheActualStyleDialog() {
        fun openStyle() {
            compose
                .onNodeWithTag("highlight-management-list")
                .performScrollToNode(
                    hasTestTag(
                        "highlight-management-edit-${dao.all.first { it.name == "Alice" }.uuid}"
                    )
                )
            compose
                .onNodeWithTag(
                    "highlight-management-edit-${dao.all.first { it.name == "Alice" }.uuid}"
                )
                .performClick()
            compose.waitUntil {
                compose.onAllNodesWithTag("highlight-rule-save").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("highlight-rule-style").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("highlight-style-font-size")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            instrumentation.runOnMainSync {
                val sheet = WindowInspector.getGlobalWindowViews()
                    .mapNotNull { it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) }
                    .single()
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                var expanded = false
                instrumentation.runOnMainSync {
                    val sheet = WindowInspector.getGlobalWindowViews()
                        .mapNotNull { it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) }
                        .singleOrNull()
                    expanded = sheet != null &&
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state ==
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
                expanded
            }
            compose.onNodeWithTag("highlight-style-font-size").performScrollTo()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                runCatching {
                    compose.onNodeWithTag("highlight-style-font-size").assertIsDisplayed()
                }.isSuccess
            }
            compose.onNodeWithTag("highlight-style-font-size").assertIsDisplayed()
        }
        fun edit(tag: String, value: Int?) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            if (value != null) {
                compose
                    .onNodeWithTag("highlight-style-number-input")
                    .performTextReplacement(value.toString())
                if (tag == "highlight-style-letter-spacing")
                    compose.onNodeWithTag("highlight-style-number-input").assertTextContains("-20")
            }
            compose
                .onNodeWithTag(
                    if (value == null) "highlight-style-number-default"
                    else "highlight-style-number-save"
                )
                .performClick()
        }
        fun save() {
            pressBack()
            compose.onNodeWithTag("highlight-rule-save").performClick()
        }
        openStyle()
        edit("highlight-style-font-size", 42)
        edit("highlight-style-letter-spacing", -20)
        screenshot("highlight-font-metrics-settings")
        save()
        await { dao.all.first().styleObj().let { it.fontSize == 42f && it.letterSpacing == -0.2f } }
        scenario!!.recreate()
        awaitRules(dao.all)
        openStyle()
        compose
            .onNodeWithTag("highlight-style-font-size")
            .performScrollTo()
            .assertTextContains(context.getString(R.string.text_size) + " · 42")
        edit("highlight-style-font-size", null)
        edit("highlight-style-letter-spacing", null)
        save()
        await { dao.all.first().styleObj().let { it.fontSize == null && it.letterSpacing == null } }
    }

    @Test
    fun pillMarginEditsPersistAndResetThroughTheActualStyleDialog() {
        fun margin(value: Int) = context.getString(R.string.highlight_pill_padding_value, value)
        fun openStyle() {
            compose
                .onNodeWithTag("highlight-management-list")
                .performScrollToNode(
                    hasTestTag(
                        "highlight-management-edit-${dao.all.first { it.name == "Alice" }.uuid}"
                    )
                )
            compose
                .onNodeWithTag(
                    "highlight-management-edit-${dao.all.first { it.name == "Alice" }.uuid}"
                )
                .performClick()
            compose.waitUntil {
                compose.onAllNodesWithTag("highlight-rule-save").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("highlight-rule-style").performScrollTo().performClick()
        }
        fun saveStyle() {
            pressBack()
            compose.onNodeWithTag("highlight-rule-save").performClick()
        }
        openStyle()
        compose
            .onNodeWithTag("highlight-style-tune-Fill")
            .performScrollTo()
            .assertTextContains(margin(125))
            .performClick()
        compose.onNodeWithTag("highlight-style-number-input").performTextReplacement("150")
        compose.onNodeWithTag("highlight-style-number-save").performClick()
        compose
            .onNodeWithTag("highlight-style-tune-Fill")
            .performScrollTo()
            .assertTextContains(margin(150))
            .assertIsDisplayed()
        compose.onNodeWithTag("highlight-style-toggle-Fill").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-toggle-Fill").performScrollTo().performClick()
        compose
            .onNodeWithTag("highlight-style-tune-Fill")
            .performScrollTo()
            .assertTextContains(margin(150))
            .assertIsDisplayed()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "highlight-pill-margin-settings.png")
                .outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        saveStyle()
        await { dao.all.first().styleObj().resolvedPillPaddingScale == 1.5f }
        scenario!!.recreate()
        awaitRules(dao.all)
        openStyle()
        compose.onNodeWithTag("highlight-style-tune-Fill").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-number-default").performClick()
        compose
            .onNodeWithTag("highlight-style-tune-Fill")
            .performScrollTo()
            .assertTextContains(margin(100))
            .assertIsDisplayed()
        saveStyle()
        await { dao.all.first().styleObj().pillPaddingScale == null }
    }

    @Test
    fun filterRenameMoveAndDeleteUseRealDialogsAndPreserveOtherRules() {
        compose.onNodeWithTag("highlight-management-menu").performClick()
        compose.waitForIdle()
        val menuBitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "highlight-more-menu.png")
                .outputStream()
                .use { assertTrue(menuBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            menuBitmap.recycle()
        }
        pressBack()
        filter("[Characters]")
        awaitRules(dao.all.filter { it.group == "Characters" })
        screenshot("highlight-group-filter")

        menu("highlight-management-groups")
        groupAction("Characters", editing = true)
        compose.onNodeWithTag("highlight-group-name").performTextReplacement("People")
        compose.onNodeWithTag("highlight-group-rename-confirm").performClick()
        awaitGroup("People")
        screenshot("highlight-group-manager")
        dismissGroupManager()
        // Renaming the active group must not leave a stale, empty filter behind.
        awaitRules(dao.all)

        filter("[People]")
        awaitRules(dao.all.filter { it.group == "People" })
        menu("highlight-management-groups")
        groupAction("People")
        compose.onNodeWithTag("highlight-group-choose-move").performClick()
        // This is a real group named like the special ungrouped option.
        compose.onNodeWithTag("highlight-group-move-$namedUngrouped").performClick()
        await { dao.all.count { it.group == namedUngrouped } == 3 }
        assertEquals("Quotes", dao.all.single { it.name == "Quote" }.group)
        assertNull(dao.all.single { it.name == "Loose rule" }.group)
        dismissGroupManager()
        awaitRules(dao.all)

        filter("[$namedUngrouped]")
        awaitRules(dao.all.filter { it.group == namedUngrouped })
        menu("highlight-management-groups")
        groupAction(namedUngrouped)
        compose.onNodeWithTag("highlight-group-choose-move").performClick()
        compose.onNodeWithTag("highlight-group-move-none").performClick()
        await { dao.all.count { it.group == null } == 4 }
        dismissGroupManager()
        awaitRules(dao.all)
        filter(context.getString(R.string.no_group))
        awaitRules(dao.all.filter { it.group == null })

        filter("[Quotes]")
        awaitRules(dao.all.filter { it.group == "Quotes" })
        menu("highlight-management-groups")
        groupAction("Quotes")
        compose.onNodeWithTag("highlight-group-delete-confirm").performClick()
        await { dao.all.size == 4 }
        assertEquals(
            fixtures.filter { it.group != "Quotes" }.map { it.uuid }.toSet(),
            dao.all.map { it.uuid }.toSet(),
        )
        dismissGroupManager()
        awaitRules(dao.all)
        screenshot("highlight-group-after-move-delete")
    }

    private fun dismissGroupManager() {
        compose.waitForIdle()
        // Send real back events to the currently focused dialog. Espresso's
        // cached root can still refer to its covered activity after a move.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun exportAllIncludesRulesHiddenByGroupFilter() {
        filter("[Characters]")
        awaitRules(dao.all.filter { it.group == "Characters" })
        val launched = AtomicReference<Intent?>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != HandleFileActivity::class.java.name)
                        return null
                    launched.set(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            menu("highlight-management-export-all")
            await { launched.get() != null }
            val intent = launched.get()!!
            assertEquals(HandleFileContract.EXPORT, intent.getIntExtra("mode", -1))
            assertEquals("HighlightRules.json", intent.getStringExtra("fileName"))
            val data = checkNotNull(IntentData.get<ByteArray>(intent.getStringExtra("fileKey")))
            val exported =
                GSON.fromJson(data.toString(Charsets.UTF_8), HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE, exported.type)
            assertEquals(
                fixtures.map { it.uuid }.toSet(),
                exported.rules!!.map { it!!.uuid }.toSet(),
            )
            assertEquals(5, exported.rules.size)
            compose.onNodeWithText(context.getString(R.string.export_success)).assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun selectedExportUploadsOnlyCheckedRulesAndShowsACopyableDownloadLink() {
        val uploaded = AtomicReference<String?>()
        val uploadedName = AtomicReference<String?>()
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    if (session.method == Method.POST && session.uri == "/upload") {
                        val files = hashMapOf<String, String>()
                        session.parseBody(files)
                        uploaded.set(File(checkNotNull(files["file"])).readText())
                        uploadedName.set(session.parameters["file"]?.single())
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"url":"http://127.0.0.1:$listeningPort/HighlightRules.json"}""",
                        )
                    }
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        uploaded.get().orEmpty(),
                    )
                }
            }
        val previousRule = DirectLinkUpload.getConfig()
        val preferences = context.defaultSharedPreferences
        val previousCronet = preferences.all[PreferKey.cronet] as Boolean?
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previousClip: android.content.ClipData? = null
        scenario!!.onActivity { previousClip = clipboard.primaryClip }
        server.start()
        try {
            preferences.edit().putBoolean(PreferKey.cronet, false).commit()
            val url = "http://127.0.0.1:${server.listeningPort}/HighlightRules.json"
            val summary = "Local export regression server"
            DirectLinkUpload.putConfig(
                DirectLinkUpload.Rule(
                    uploadUrl =
                        "http://127.0.0.1:${server.listeningPort}/upload," +
                            """{"method":"POST","body":{"file":"fileRequest"},"type":"multipart/form-data"}""",
                    downloadUrlRule = "$.url",
                    summary = summary,
                )
            )
            val expected = dao.all.first()
            compose.onNodeWithTag("highlight-management-select-${expected.uuid}").performClick()
            compose.onNodeWithTag("highlight-management-selection-menu").performClick()
            compose.onNodeWithTag("highlight-management-export").performClick()
            compose.onNodeWithText(context.getString(R.string.upload_url)).performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithTag("highlight-management-export-result")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("highlight-management-export-result").assertTextEquals(url)
            compose.onNodeWithTag("highlight-management-export-summary").assertTextEquals(summary)
            compose.onNodeWithText(context.getString(R.string.export_success)).assertIsDisplayed()
            assertEquals("HighlightRules.json", uploadedName.get())
            val downloaded = URL(url).readText()
            assertEquals(uploaded.get(), downloaded)
            val exported = GSON.fromJson(downloaded, HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE, exported.type)
            assertEquals(GSON.toJson(expected), GSON.toJson(exported.rules!!.single()))
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.getExternalFilesDir("ui-regression"), "highlight-export-success.png")
                    .outputStream()
                    .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            compose.onNodeWithTag("highlight-management-export-copy").performClick()
            await {
                var copied = false
                scenario!!.onActivity { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() == url }
                copied
            }
        } finally {
            if (previousRule == null) DirectLinkUpload.delConfig()
            else DirectLinkUpload.putConfig(previousRule)
            preferences
                .edit()
                .apply {
                    if (previousCronet == null) remove(PreferKey.cronet)
                    else putBoolean(PreferKey.cronet, previousCronet)
                }
                .commit()
            scenario!!.onActivity {
                previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            server.stop()
            // Clearing the clipboard leaves SystemUI's preview covering later tests' touch targets.
            instrumentation.uiAutomation
                .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                .use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .bufferedReader()
                        .use {
                            assertTrue(it.readText().contains("Broadcast completed"))
                        }
                }
            instrumentation.uiAutomation.waitForIdle(200, 5_000)
        }
    }

    @Test
    fun importingGroupAndEnabledChangesRefreshesBothVisibleFields() {
        val changed = dao.all.first().copy(group = "Updated", isEnabled = false)
        dao.importRules(listOf(changed))
        awaitRules(dao.all)
        screenshot("highlight-group-import-refresh")
    }

    private fun menu(tag: String) {
        compose.onNodeWithTag("highlight-management-menu").performClick()
        compose.onNodeWithTag(tag).performClick()
    }

    private fun filter(label: String) {
        menu("highlight-management-filter")
        val tag =
            when (label) {
                context.getString(R.string.all) -> "highlight-filter-all"
                context.getString(R.string.no_group) -> "highlight-filter-ungrouped"
                else -> "highlight-filter-${label.removePrefix("[").removeSuffix("]")}"
            }
        compose
            .onNodeWithTag("highlight-management-filter-list")
            .performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun groupAction(group: String, editing: Boolean = false) {
        awaitGroup(group)
        val tag = if (editing) "highlight-group-edit-$group" else "highlight-group-delete-$group"
        compose.onNodeWithTag(tag).performClick()
    }

    private fun awaitGroup(group: String) {
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithTag("highlight-group-label-$group")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun awaitRules(expected: List<HighlightRule>) {
        compose.waitUntil(15_000) {
            var matches = false
            scenario!!.onActivity { activity ->
                matches =
                    activity.viewModel.state.value.visible.map { it.uuid } ==
                        expected.map { it.uuid }
            }
            matches
        }
        expected.forEach { rule ->
            compose
                .onNodeWithTag("highlight-management-list")
                .performScrollToNode(hasTestTag("highlight-management-row-${rule.uuid}"))
            val label =
                rule.group?.takeIf { it.isNotBlank() }?.let { "[$it] ${rule.getDisplayName()}" }
                    ?: rule.getDisplayName()
            compose.onNodeWithTag("highlight-management-name-${rule.uuid}").assertTextEquals(label)
            compose
                .onNodeWithTag("highlight-management-enabled-${rule.uuid}")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ToggleableState,
                        if (rule.isEnabled) ToggleableState.On else ToggleableState.Off,
                    )
                )
        }
    }

    private fun groupDialog(activity: HighlightRuleActivity) =
        activity.supportFragmentManager.fragments
            .filterIsInstance<HighlightGroupManageDialog>()
            .firstOrNull()

    private fun await(condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        assertTrue("Highlight groups did not reach the expected state", condition())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        lateinit var window: Window
        lateinit var bitmap: Bitmap
        scenario!!.onActivity { activity ->
            fun focusedWindow(fragment: androidx.fragment.app.Fragment): Window? {
                if (!fragment.isAdded) return null
                return (fragment as? androidx.fragment.app.DialogFragment)?.dialog?.window?.takeIf {
                    it.decorView.hasWindowFocus()
                } ?: fragment.childFragmentManager.fragments.firstNotNullOfOrNull(::focusedWindow)
            }
            window =
                activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::focusedWindow)
                    ?: activity.window
            if (name == "highlight-font-metrics-settings") assertTrue(window !== activity.window)
            val decor = window.decorView
            assertTrue(decor.isHardwareAccelerated)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            decor.postInvalidateOnAnimation()
        }
        try {
            assertTrue("Highlight frame was not committed", frame.await(5, TimeUnit.SECONDS))
            val copied = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync {
                PixelCopy.request(
                    window,
                    bitmap,
                    {
                        result = it
                        copied.countDown()
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            assertTrue(copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
