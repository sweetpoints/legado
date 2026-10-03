package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagePopupActionMigrationTest {

    @Test
    fun `shared popup adds vertical danger styling without losing existing behavior`() {
        val popup = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupAction.kt")
        val builder = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionMenu.kt")
        val content = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionContent.kt")
        listOf(
                "applyMd3PopupStyle()",
                "resolveDropDownYOffset(",
                "PopupActionOwner()",
                "setViewTreeLifecycleOwner(owner)",
                "setViewTreeSavedStateRegistryOwner(owner)",
                "setParentCompositionContext(recomposer)",
                "view.disposeComposition()",
                "fun setActionItems(items: List<PopupActionItem>)",
                "fun setDangerValues(values: Set<String>)",
                "fun setDisabledValues(values: Set<String>)",
            )
            .forEach { expected -> assertContains("PopupAction.kt", popup, expected) }
        listOf(
                "items.any { it.icon != null }",
                "items.any { it.checkable || it.checked }",
                "widthIn(min = 112.dp, max = 280.dp)",
                "width(IntrinsicSize.Max)",
                "FlowRow(",
                "heightIn(min = 48.dp)",
                "Role.Checkbox",
                "Modifier.toggleable(",
                "item.enabled && item.value !in disabledValues",
                "colors.disabled",
                "colors.danger",
                "Key.Escape",
                "focus.moveFocus(direction)",
            )
            .forEach { expected -> assertContains("PopupActionContent.kt", content, expected) }
        assertFalse(popup.contains("RecyclerAdapter"))
        assertFalse(popup.contains("PopupActionBinding"))
        assertFalse(content.contains("AndroidView"))

        listOf(
                "setVertical(true)",
                "setDangerValues(dangerValues)",
                "dismiss()",
                "showAsDropDown(anchor, 0, 4.dpToPx())",
            )
            .forEach { expected -> assertContains("PopupActionMenu.kt", builder, expected) }
    }

    @Test
    fun `toolbar overflow uses exact items and keeps native fallbacks`() {
        val bridge =
            readProjectFile("src/main/java/io/legado/app/utils/ToolbarOverflowMenuExtensions.kt")

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
                "abc_action_menu_overflow_description",
            )
            .forEach { expected ->
                assertContains("ToolbarOverflowMenuExtensions.kt", bridge, expected)
            }
        assertFalse(bridge.contains("WeakHashMap"))
        assertFalse(bridge.contains("setOnHierarchyChangeListener"))
        assertFalse(bridge.contains("performIdentifierAction"))
    }

    @Test
    fun `themed activity keeps the shared AppCompat toolbar and menu hooks`() {
        val activity = readProjectFile("src/main/java/io/legado/app/base/BaseThemedActivity.kt")

        listOf(
                "if (view is Toolbar) view.installMd3OverflowMenu()",
                "menu.applyTint(this, toolBarTheme)",
                "onCompatCreateOptionsMenu(menu)",
                "onCompatOptionsItemSelected(item)",
            )
            .forEach { expected -> assertContains("BaseThemedActivity.kt", activity, expected) }
        assertFalse(activity.contains("findViewById<TitleBar>"))
        assertFalse(activity.contains("installActivityOverflowMenu"))
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
