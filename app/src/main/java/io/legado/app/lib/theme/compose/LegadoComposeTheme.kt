package io.legado.app.lib.theme.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryColorDark
import io.legado.app.lib.theme.primaryDisabledTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryDisabledTextColor
import io.legado.app.lib.theme.secondaryTextColor

/**
 * 阅读现有主题体系（[io.legado.app.lib.theme.ThemeStore] + `lib/theme/MaterialValueHelper.kt`）
 * 在 Compose 世界的映射。
 *
 * 引入原因：旧页面通过 `Context.primaryColor` 之类的扩展属性取色，新 Compose 页面若直接用
 * Material3 默认配色，两套皮肤会同时出现在一个界面里。这里把旧体系的取值收敛成
 * [LegadoColors]，再喂给 [MaterialTheme]，保证新老页面配色一致。
 */
@Immutable
data class LegadoColors(
    val primary: Color,
    val primaryDark: Color,
    /** 主色之上的前景色（由主色亮度推导，项目里对应 `getToolbarTextColor`）。 */
    val onPrimary: Color,
    val accent: Color,
    val background: Color,
    val bottomBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPrimaryDisabled: Color,
    val textSecondaryDisabled: Color,
    /** 与 [io.legado.app.lib.theme.isDarkTheme] 一致，注意它由主色亮度决定，而非系统深色模式。 */
    val isLight: Boolean,
)

val LocalLegadoColors = staticCompositionLocalOf<LegadoColors> {
    error("未提供 LegadoColors，请在 LegadoComposeTheme 内使用")
}

/**
 * 取当前主题配色。
 *
 * 缓存策略：主题色变更时 [io.legado.app.help.config.ThemeConfig] 会 post
 * `EventBus.RECREATE` 并让 Activity `recreate()`，因此以 `context` 为 key 缓存即可覆盖
 * 主题与配置变化；取值本身会读 SharedPreferences，不能在每次重组时都算。
 */
@Composable
fun rememberLegadoColors(): LegadoColors {
    val context = LocalContext.current
    return remember(context) { context.toLegadoColors() }
}

@Composable
fun LegadoComposeTheme(
    colors: LegadoColors = rememberLegadoColors(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(colors) {
        val onPrimary = colors.onPrimary
        if (colors.isLight) {
            lightColorScheme(
                primary = colors.primary,
                onPrimary = onPrimary,
                primaryContainer = colors.primaryDark,
                onPrimaryContainer = onPrimary,
                secondary = colors.accent,
                onSecondary = onPrimary,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.background,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.bottomBackground,
                onSurfaceVariant = colors.textSecondary,
            )
        } else {
            darkColorScheme(
                primary = colors.primary,
                onPrimary = onPrimary,
                primaryContainer = colors.primaryDark,
                onPrimaryContainer = onPrimary,
                secondary = colors.accent,
                onSecondary = onPrimary,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.background,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.bottomBackground,
                onSurfaceVariant = colors.textSecondary,
            )
        }
    }
    CompositionLocalProvider(LocalLegadoColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

private fun Context.toLegadoColors(): LegadoColors {
    val primary = Color(primaryColor)
    return LegadoColors(
        primary = primary,
        primaryDark = Color(primaryColorDark),
        // 主色之上的前景色取决于主色亮度，不能沿用页面文字色
        onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White,
        accent = Color(accentColor),
        background = Color(backgroundColor),
        bottomBackground = Color(bottomBackground),
        textPrimary = Color(primaryTextColor),
        textSecondary = Color(secondaryTextColor),
        textPrimaryDisabled = Color(primaryDisabledTextColor),
        textSecondaryDisabled = Color(secondaryDisabledTextColor),
        isLight = !isDarkTheme,
    )
}
