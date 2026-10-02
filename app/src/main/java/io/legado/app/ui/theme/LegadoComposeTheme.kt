package io.legado.app.ui.theme

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
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryColorDark
import io.legado.app.utils.ColorUtils

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
    /**
     * 明暗由**背景色**亮度判定（`onBackground`/`onSurface` 必须与 `background` 对比）。
     *
     * 注意不要用 [io.legado.app.lib.theme.isDarkTheme]：那个是「主色是否浅」，
     * 与背景明暗可能相反——默认浅蓝主色会让它得到 `false`，从而取出白色文字放到浅灰背景上。
     */
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
    val backgroundArgb = backgroundColor
    // 明暗看背景色，不看主色：ColorScheme 的 onBackground/onSurface 必须与 background 形成对比
    val isLight = ColorUtils.isColorLight(backgroundArgb)
    return LegadoColors(
        primary = primary,
        primaryDark = Color(primaryColorDark),
        // 主色之上的前景色取决于主色亮度，不能沿用页面文字色
        onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White,
        accent = Color(accentColor),
        background = Color(backgroundArgb),
        bottomBackground = Color(bottomBackground),
        // 文字色刻意走「资源限定符」，与 View 页面完全一致
        // （view_preference.xml 用的是 @color/primaryText / @color/tv_text_summary，
        //   它们随 AppConfig.isNightTheme 驱动的日夜模式切换）。
        //
        // ⚠️ 不要改用 getPrimaryTextColor(isDarkTheme) / primaryTextColor 这类 helper：
        // 它们按「主色亮度」判定，而默认主色 md_light_blue_600 亮度仅 0.29（< 0.5），
        // 会取出 md_dark_primary_text = #FFFFFFFF（白字），可背景是 md_grey_50（近白）
        // —— 就是这个 bug 导致 About 页白字白底看不清。
        textPrimary = Color(ContextCompat.getColor(this, R.color.primaryText)),
        textSecondary = Color(ContextCompat.getColor(this, R.color.tv_text_summary)),
        textPrimaryDisabled = Color(
            ContextCompat.getColor(
                this,
                if (isLight) R.color.md_light_disabled else R.color.md_dark_disabled
            )
        ),
        textSecondaryDisabled = Color(
            ContextCompat.getColor(
                this,
                if (isLight) {
                    androidx.appcompat.R.color.secondary_text_disabled_material_light
                } else {
                    androidx.appcompat.R.color.secondary_text_disabled_material_dark
                }
            )
        ),
        isLight = isLight,
    )
}
