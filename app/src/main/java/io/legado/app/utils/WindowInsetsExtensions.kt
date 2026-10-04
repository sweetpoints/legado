package io.legado.app.utils

import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat

val WindowInsetsCompat.navigationBarHeight
    get() = (getInsets(WindowInsetsCompat.Type.systemBars()).bottom - imeHeight).coerceAtLeast(0)

val WindowInsetsCompat.imeHeight
    get() = getInsets(WindowInsetsCompat.Type.ime()).bottom

/**
 * Compose still requires adjustResize to receive IME insets on supported older releases.
 * Keep the deprecated platform flag confined to this compatibility boundary.
 * https://developer.android.com/develop/ui/compose/system/setup-e2e
 */
@Suppress("DEPRECATION")
fun Window.resizeForIme() {
    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}
