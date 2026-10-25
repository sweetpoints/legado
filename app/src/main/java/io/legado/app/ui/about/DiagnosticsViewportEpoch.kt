package io.legado.app.ui.about

/** A viewport is retired only by explicit navigation, never by a live list projection. */
internal class DiagnosticsViewportEpoch {
    var value: Long = 0
        private set
    fun retire() { value++ }
    fun accepts(receipt: Long): Boolean = receipt == value
}
