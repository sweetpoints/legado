package io.legado.app.base

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import io.legado.app.constant.Theme
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.theme.LocalLegadoColors
import io.legado.app.utils.setLightStatusBar

/** Compose host retaining the app's locale, window, background and back behavior. */
abstract class BaseComposeActivity(
    fullScreen: Boolean = true,
    theme: Theme = Theme.Auto,
    toolBarTheme: Theme = Theme.Auto,
    transparent: Boolean = false,
    imageBg: Boolean = true,
    showOpenMenuIcon: Boolean = true,
) : BaseThemedActivity(fullScreen, theme, toolBarTheme, transparent, imageBg, showOpenMenuIcon) {
    /** Immersive readers and the main shell own their system-bar layout themselves. */
    protected open val handlesWindowInsets: Boolean = false

    // The composition is installed after observers and synchronous initialization.
    final override fun createContentView() = Unit

    final override fun onActivityCreated(savedInstanceState: Bundle?) {
        onComposeCreated(savedInstanceState)
        if (!handlesWindowInsets) WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LegadoComposeTheme {
                if (handlesWindowInsets) {
                    Content(savedInstanceState)
                } else {
                    val lightBackground = LocalLegadoColors.current.isLight
                    SideEffect { setLightStatusBar(lightBackground) }
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    ) { Content(savedInstanceState) }
                }
            }
        }
    }

    @Composable abstract fun Content(savedInstanceState: Bundle?)

    open fun onComposeCreated(savedInstanceState: Bundle?) = Unit
}
