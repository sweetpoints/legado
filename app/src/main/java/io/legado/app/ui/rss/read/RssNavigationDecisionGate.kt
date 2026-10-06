package io.legado.app.ui.rss.read

/** Main-thread state for asynchronous navigation decisions and one approved replay. */
class RssNavigationDecisionGate {
    private var generation = 0L
    private var approved: Pair<Long, String>? = null

    fun begin(): Long {
        approved = null
        return ++generation
    }

    fun isCurrent(token: Long): Boolean = token == generation

    fun approve(token: Long, url: String): Boolean {
        if (!isCurrent(token)) return false
        approved = token to url
        return true
    }

    fun consumeBypass(url: String): Boolean {
        val pending = approved ?: return false
        approved = null
        return pending.first == generation && pending.second == url
    }

    fun invalidate() {
        begin()
    }
}
