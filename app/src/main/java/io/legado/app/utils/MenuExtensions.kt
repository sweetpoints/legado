@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.appcompat.widget.SearchView
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.primaryTextColor
import java.lang.reflect.Method

/** Inflate a support command model without showing a native popup. */
fun Context.inflateMenuModel(@MenuRes resource: Int): Menu =
    PopupMenu(this, View(this)).apply { inflate(resource) }.menu

@SuppressLint("RestrictedApi")
@Suppress("UsePropertyAccessSyntax")
fun Menu.applyTint(
    context: Context,
    theme: Theme = Theme.Auto,
    transparentBar: Boolean = false,
): Menu =
    this.let { menu ->
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        val tintColor =
            MenuExtensions.getMenuColor(
                context,
                theme,
                transparentBar = transparentBar,
            )
        menu.forEach { item ->
            (item as MenuItemImpl).let { impl ->
                // overflow：展开的item
                impl.icon?.setTintMutate(
                    if (impl.requiresOverflow()) defaultTextColor else tintColor
                )
                (impl.actionView as? SearchView)?.applyTint(tintColor)
            }
        }
        return menu
    }

@SuppressLint("RestrictedApi")
fun Menu.applyOpenTint(context: Context, showIcon: Boolean = true) {
    // 展开菜单显示图标
    if (this.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        kotlin.runCatching {
            var method: Method =
                this.javaClass.getDeclaredMethod("setOptionalIconsVisible", java.lang.Boolean.TYPE)
            method.isAccessible = true
            method.invoke(this, showIcon)
            if (showIcon) {
                method = this.javaClass.getDeclaredMethod("getNonActionItems")
                val menuItems = method.invoke(this)
                if (menuItems is ArrayList<*>) {
                    for (menuItem in menuItems) {
                        if (menuItem is MenuItem) {
                            menuItem.icon?.setTintMutate(defaultTextColor)
                        }
                    }
                }
            }
        }
    } else if (this.javaClass.simpleName.equals("SubMenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        (this as? SubMenuBuilder)?.forEach { item: MenuItem ->
            item.icon?.setTintMutate(defaultTextColor)
        }
    }
}

@SuppressLint("RestrictedApi")
inline fun Menu.transaction(block: (Menu) -> Unit) {
    val menuBuilder = this as? MenuBuilder
    menuBuilder?.stopDispatchingItemsChanged()
    try {
        block(this)
    } finally {
        menuBuilder?.startDispatchingItemsChanged()
    }
}

object MenuExtensions {

    fun getMenuColor(
        context: Context,
        theme: Theme = Theme.Auto,
        requiresOverflow: Boolean = false,
        transparentBar: Boolean = false,
    ): Int {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        if (requiresOverflow) return defaultTextColor
        val primaryTextColor = context.primaryTextColor
        return when (theme) {
            Theme.Dark -> context.getCompatColor(R.color.md_white_1000)
            Theme.Light -> context.getCompatColor(R.color.md_black_1000)
            else -> if (transparentBar) context.getToolbarTextColor(true) else primaryTextColor
        }
    }
}
