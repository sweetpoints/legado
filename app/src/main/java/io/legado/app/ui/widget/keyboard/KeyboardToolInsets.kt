package io.legado.app.ui.widget.keyboard

internal fun resolveKeyboardToolHeight(
    imeVisible: Boolean?,
    imeInsetBottom: Int,
    screenHeight: Int,
    visibleFrameBottom: Int,
): Int {
    if (imeVisible != null) {
        return if (imeVisible) imeInsetBottom.coerceAtLeast(0) else 0
    }

    if (screenHeight <= 0 || visibleFrameBottom !in 1..screenHeight) {
        return 0
    }
    val hiddenHeight = screenHeight - visibleFrameBottom
    return hiddenHeight.takeIf { it > screenHeight / 5 } ?: 0
}
