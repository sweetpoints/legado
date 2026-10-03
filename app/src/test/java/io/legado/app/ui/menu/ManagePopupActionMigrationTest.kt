package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagePopupActionMigrationTest {

    @Test
    fun `shared popup adds vertical danger styling without losing existing behavior`() {
        val popup = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupAction.kt")
        val builder = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionMenu.kt")
        val row = readProjectFile("src/main/res/layout/item_popup_action.xml")

        listOf(
            "applyMd3PopupStyle()",
            "resolveDropDownYOffset(",
            "LinearLayoutManager(context)",
            "private var actionItems: List<PopupActionItem> = emptyList()",
            "items.any { it.icon != null }",
            "items.any { it.checkable }",
            "textView.measuredWidth",
            "textView.minHeight = 48.dpToPx()",
            "textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())",
            "val enabled = isItemEnabled(item)",
            "item.enabled && item.value !in disabledValues",
            "context.secondaryDisabledTextColor",
            "info.isCheckable = item.checkable",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE",
            "imageView.visibility = View.INVISIBLE",
            "takeIf(::isItemEnabled)"
        ).forEach { expected -> assertContains("PopupAction.kt", popup, expected) }
        listOf(
            "@+id/iv_icon",
            "@+id/text_view",
            "@+id/iv_check_end",
            "android:duplicateParentState=\"true\""
        ).forEach { expected -> assertContains("item_popup_action.xml", row, expected) }
        assertFalse(row.contains("app:tint="))

        listOf(
            "setVertical(true)",
            "setDangerValues(dangerValues)",
            "dismiss()",
            "showAsDropDown(anchor, 0, 4.dpToPx())"
        ).forEach { expected -> assertContains("PopupActionMenu.kt", builder, expected) }
    }

    @Test
    fun `toolbar overflow uses exact items and keeps native fallbacks`() {
        val bridge = readProjectFile(
            "src/main/java/io/legado/app/utils/ToolbarOverflowMenuExtensions.kt"
        )

        listOf(
            "getTag(R.id.toolbar_overflow_menu_state) as? OverflowMenuState",
            "setTag(R.id.toolbar_overflow_menu_state, newState)",
            "addOnLayoutChangeListener",
            "onPrepareMenu(menu)",
            "onOpenCustomMenu(menu)",
            "actionItems.hasUnsupportedItems()",
            "item.subMenu != null || item.actionView != null",
            "showOverflowMenu()",
            "actionItems.mapIndexed { index, item ->",
            "value = index.toString()",
            "icon = if (state.showIcons)",
            "enabled = item.isEnabled",
            "checkable = item.isCheckable",
            "checked = item.isChecked",
            "action.toIntOrNull()",
            "actionItems::getOrNull",
            "performItemAction(menuItem, 0)",
            "params.isOverflowButton",
            "abc_action_menu_overflow_description"
        ).forEach { expected ->
            assertContains("ToolbarOverflowMenuExtensions.kt", bridge, expected)
        }
        assertFalse(bridge.contains("WeakHashMap"))
        assertFalse(bridge.contains("setOnHierarchyChangeListener"))
        assertFalse(bridge.contains("performIdentifierAction"))
    }

    @Test
    fun `base activity and fragments install the toolbar overflow bridge`() {
        val activity = readProjectFile("src/main/java/io/legado/app/base/BaseThemedActivity.kt")
        val fragment = readProjectFile("src/main/java/io/legado/app/base/BaseFragment.kt")

        listOf(
            "if (view is Toolbar) view.installMd3OverflowMenu()",
            "?: findViewById(R.id.titleBar)",
            "?.installActivityOverflowMenu()",
            "showIcons = showOpenMenuIcon",
            "onPrepareOptionsMenu(toolbarMenu)",
            "onMenuOpened(Window.FEATURE_OPTIONS_PANEL, toolbarMenu)"
        ).forEach { expected -> assertContains("BaseThemedActivity.kt", activity, expected) }
        assertFalse(activity.contains("if (view is Toolbar) view.installActivityOverflowMenu()"))
        assertContains("BaseFragment.kt", fragment, "it.installMd3OverflowMenu()")
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

}
