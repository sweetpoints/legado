package io.legado.app.ui.code

internal fun shouldShowDebugSourceAction(writable: Boolean, requested: Boolean): Boolean {
    return writable && requested
}

internal fun shouldShowLoginSourceAction(writable: Boolean, requested: Boolean): Boolean {
    return writable && requested
}

internal fun shouldShowJavaScriptSyntaxAction(
    useSafeEditor: Boolean,
    requested: Boolean,
): Boolean {
    return !useSafeEditor && requested
}
