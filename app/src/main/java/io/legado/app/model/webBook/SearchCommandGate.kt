package io.legado.app.model.webBook

import java.util.concurrent.atomic.AtomicLong

/** Keeps the original command fence contract available independently of the old UI model. */
internal class SearchCommandGate {
    private val sequence = AtomicLong()
    private val lock = Any()

    fun next(): Long = sequence.incrementAndGet()

    fun runIfCurrent(command: Long, block: () -> Unit): Boolean =
        synchronized(lock) {
            if (command != sequence.get()) return false
            block()
            true
        }

    fun invalidate(block: () -> Unit) {
        sequence.incrementAndGet()
        synchronized(lock) { block() }
    }
}
