package io.legado.app.ui.theme

import android.content.Context
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryColorDark

/**
 * User theme seeds retained for compatibility; Compose receives a complete accessible M3 scheme.
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
     * 注意不要用 [io.legado.app.lib.theme.isDarkTheme]：那个是「主色是否浅」， 与背景明暗可能相反——默认浅蓝主色会让它得到
     * `false`，从而取出白色文字放到浅灰背景上。
     */
    val isLight: Boolean,
)

val LocalLegadoColors =
    staticCompositionLocalOf<LegadoColors> {
        error("未提供 LegadoColors，请在 LegadoComposeTheme 内使用")
    }

/**
 * 取当前主题配色。
 *
 * 缓存策略：主题色变更时 [io.legado.app.help.config.ThemeConfig] 会 post `EventBus.RECREATE` 并让 Activity
 * `recreate()`，因此以 `context` 为 key 缓存即可覆盖 主题与配置变化；取值本身会读 SharedPreferences，不能在每次重组时都算。
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
    val roles = remember(colors) { colors.material3Roles() }
    val accessibleColors =
        remember(colors, roles) {
            colors.copy(
                onPrimary = Color(contrastingForeground(colors.primary.toArgb())),
                accent = Color(roles["accentForeground"]),
                background = Color(roles["background"]),
                bottomBackground = Color(roles["surfaceVariant"]),
                textPrimary = Color(roles["onSurface"]),
                textSecondary = Color(roles["onSurfaceVariant"]),
                isLight = contrastingForeground(roles["background"]) == 0xFF000000.toInt(),
            )
        }
    val colorScheme =
        remember(roles) {
            // Both factories are completely supplied: no baseline purple/error/container
            // roles leak into a user-defined palette.
            val base = if (accessibleColors.isLight) lightColorScheme() else darkColorScheme()
            base.copy(
                primary = Color(roles["primary"]),
                onPrimary = Color(roles["onPrimary"]),
                primaryContainer = Color(roles["primaryContainer"]),
                onPrimaryContainer = Color(roles["onPrimaryContainer"]),
                inversePrimary = Color(roles["inversePrimary"]),
                secondary = Color(roles["secondary"]),
                onSecondary = Color(roles["onSecondary"]),
                secondaryContainer = Color(roles["secondaryContainer"]),
                onSecondaryContainer = Color(roles["onSecondaryContainer"]),
                tertiary = Color(roles["tertiary"]),
                onTertiary = Color(roles["onTertiary"]),
                tertiaryContainer = Color(roles["tertiaryContainer"]),
                onTertiaryContainer = Color(roles["onTertiaryContainer"]),
                background = Color(roles["background"]),
                onBackground = Color(roles["onBackground"]),
                surface = Color(roles["surface"]),
                onSurface = Color(roles["onSurface"]),
                surfaceVariant = Color(roles["surfaceVariant"]),
                onSurfaceVariant = Color(roles["onSurfaceVariant"]),
                surfaceTint = Color(roles["surfaceTint"]),
                inverseSurface = Color(roles["inverseSurface"]),
                inverseOnSurface = Color(roles["inverseOnSurface"]),
                error = Color(roles["error"]),
                onError = Color(roles["onError"]),
                errorContainer = Color(roles["errorContainer"]),
                onErrorContainer = Color(roles["onErrorContainer"]),
                outline = Color(roles["outline"]),
                outlineVariant = Color(roles["outlineVariant"]),
                scrim = Color(roles["scrim"]),
                surfaceBright = Color(roles["surfaceBright"]),
                surfaceContainer = Color(roles["surfaceContainer"]),
                surfaceContainerHigh = Color(roles["surfaceContainerHigh"]),
                surfaceContainerHighest = Color(roles["surfaceContainerHighest"]),
                surfaceContainerLow = Color(roles["surfaceContainerLow"]),
                surfaceContainerLowest = Color(roles["surfaceContainerLowest"]),
                surfaceDim = Color(roles["surfaceDim"]),
                primaryFixed = Color(roles["primaryFixed"]),
                primaryFixedDim = Color(roles["primaryFixedDim"]),
                onPrimaryFixed = Color(roles["onPrimaryFixed"]),
                onPrimaryFixedVariant = Color(roles["onPrimaryFixedVariant"]),
                secondaryFixed = Color(roles["secondaryFixed"]),
                secondaryFixedDim = Color(roles["secondaryFixedDim"]),
                onSecondaryFixed = Color(roles["onSecondaryFixed"]),
                onSecondaryFixedVariant = Color(roles["onSecondaryFixedVariant"]),
                tertiaryFixed = Color(roles["tertiaryFixed"]),
                tertiaryFixedDim = Color(roles["tertiaryFixedDim"]),
                onTertiaryFixed = Color(roles["onTertiaryFixed"]),
                onTertiaryFixedVariant = Color(roles["onTertiaryFixedVariant"]),
            )
        }
    CompositionLocalProvider(LocalLegadoColors provides accessibleColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = Shapes(),
        ) {
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, content)
            }
        }
    }
}

private fun Context.toLegadoColors(): LegadoColors {
    val primary = Color(primaryColor)
    val backgroundArgb = backgroundColor
    // 明暗看背景色，不看主色：ColorScheme 的 onBackground/onSurface 必须与 background 形成对比
    val isLight = contrastingForeground(backgroundArgb) == 0xFF000000.toInt()
    return LegadoColors(
        primary = primary,
        primaryDark = Color(primaryColorDark),
        // 主色之上的前景色取决于主色亮度，不能沿用页面文字色
        onPrimary = Color(contrastingForeground(primary.toArgb())),
        accent = Color(accentColor),
        background = Color(backgroundArgb),
        bottomBackground = Color(bottomBackground),
        // Resource colors are preferences, not trusted foregrounds: the M3 mapping
        // validates both primary and secondary text against the actual surface family.
        textPrimary = Color(ContextCompat.getColor(this, R.color.primaryText)),
        textSecondary = Color(ContextCompat.getColor(this, R.color.tv_text_summary)),
        textPrimaryDisabled =
            Color(
                ContextCompat.getColor(
                    this,
                    if (isLight) R.color.md_light_disabled else R.color.md_dark_disabled,
                )
            ),
        textSecondaryDisabled =
            Color(
                ContextCompat.getColor(
                    this,
                    if (isLight) {
                        androidx.appcompat.R.color.secondary_text_disabled_material_light
                    } else {
                        androidx.appcompat.R.color.secondary_text_disabled_material_dark
                    },
                )
            ),
        isLight = isLight,
    )
}

private fun LegadoColors.material3Roles(): Material3ColorRoles =
    material3ColorRoles(
        primary.toArgb(),
        primaryDark.toArgb(),
        accent.toArgb(),
        background.toArgb(),
        bottomBackground.toArgb(),
        textPrimary.toArgb(),
        textSecondary.toArgb(),
    )
