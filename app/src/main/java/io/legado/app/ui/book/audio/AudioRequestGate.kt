package io.legado.app.ui.book.audio

/** A network result must own both the request generation and the engine book it observed. */
internal class AudioRequestGate {
    private var generation = 0L

    @Synchronized fun claim(): Long = ++generation

    @Synchronized fun current(owner: Long): Boolean = owner == generation

    @Synchronized
    fun retire(owner: Long) {
        if (owner == generation) generation++
    }

    @Synchronized
    fun publish(owner: Long, update: () -> Unit): Boolean {
        if (owner != generation) return false
        update()
        return true
    }

    @Synchronized
    fun publish(
        owner: Long,
        expectedBook: String?,
        currentBook: () -> String?,
        update: () -> Unit,
    ): Boolean {
        if (owner != generation || currentBook() != expectedBook) return false
        update()
        return true
    }
}
