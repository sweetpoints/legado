package io.legado.app.ui.book.read.config

/** One visible View owns one reader counter lease, independent of duplicate dismiss callbacks. */
internal class PaddingPanelVisibility {
    interface Owner { var bottomDialog: Int }
    private var owner: Owner? = null
    fun acquire(owner: Owner) {
        if (this.owner === owner) return
        release()
        this.owner = owner
        owner.bottomDialog++
    }
    fun release() {
        val previous = owner ?: return
        owner = null
        previous.bottomDialog = (previous.bottomDialog - 1).coerceAtLeast(0)
    }
}
