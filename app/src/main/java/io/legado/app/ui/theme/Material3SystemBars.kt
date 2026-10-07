package io.legado.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/** System icon colors follow the actual Compose surface behind each system bar. */
@Composable
@Suppress("DEPRECATION")
fun Material3SystemBars(statusBackground: Color, navigationBackground: Color) {
    var context = LocalContext.current
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    val activity = context as? Activity ?: return
    SideEffect {
        val window = activity.window
        // Compose draws behind the status bar. Older Android navigation bars still
        // need their own background; newer Android draws the Compose surface.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT < 35) window.navigationBarColor = navigationBackground.toArgb()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars =
            contrastingForeground(statusBackground.toArgb()) == android.graphics.Color.BLACK
        controller.isAppearanceLightNavigationBars =
            contrastingForeground(navigationBackground.toArgb()) == android.graphics.Color.BLACK
    }
}
