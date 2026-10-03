package io.legado.app.model

/** Serializes reader resets and publication without holding a lock during image parsing. */
internal class MangaContentOwnerGate<T : Any>(private val lock: Any = Any()) {
    internal data class Token<T>(val owner: T, val epoch: Long)

    private var epoch = 0L

    fun capture(owner: T): Token<T> = synchronized(lock) { Token(owner, epoch) }

    fun invalidate(action: () -> Unit) =
        synchronized(lock) {
            epoch++
            action()
        }

    fun accept(token: Token<T>, currentOwner: () -> T?, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (token.epoch != epoch || token.owner !== currentOwner()) {
                false
            } else {
                action()
                true
            }
        }
}
