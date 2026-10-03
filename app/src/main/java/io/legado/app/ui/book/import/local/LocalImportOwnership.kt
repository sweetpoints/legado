package io.legado.app.ui.book.import.local

/** Listings and native receipts belong to the directory request that accepted them. */
internal class LocalImportOwnership {
    private var generation = 0L

    @Synchronized fun begin(): Long = ++generation

    @Synchronized fun current(owner: Long) = generation == owner

    @Synchronized
    fun publish(owner: Long, block: () -> Unit): Boolean {
        if (!current(owner)) return false
        block()
        return true
    }
}
