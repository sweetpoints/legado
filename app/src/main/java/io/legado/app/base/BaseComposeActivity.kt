package io.legado.app.base

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import io.legado.app.constant.Theme
import io.legado.app.ui.theme.LegadoComposeTheme

/** Compose host retaining the app's locale, window, background and back behavior. */
abstract class BaseComposeActivity(
    fullScreen: Boolean = true,
    theme: Theme = Theme.Auto,
    toolBarTheme: Theme = Theme.Auto,
    transparent: Boolean = false,
    imageBg: Boolean = true,
    showOpenMenuIcon: Boolean = true,
) : BaseThemedActivity(fullScreen, theme, toolBarTheme, transparent, imageBg, showOpenMenuIcon) {
    // The composition is installed after observers and synchronous initialization.
    final override fun createContentView() = Unit

    final override fun onActivityCreated(savedInstanceState: Bundle?) {
        onComposeCreated(savedInstanceState)
        setContent {
            LegadoComposeTheme {
                Content(savedInstanceState)
            }
        }
    }

    @Composable abstract fun Content(savedInstanceState: Bundle?)

    open fun onComposeCreated(savedInstanceState: Bundle?) = Unit
}
