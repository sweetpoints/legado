package io.legado.app.help.gsyVideo

/** Main-thread owner version: even a replacement using the same URL invalidates an old prompt. */
internal class VideoPlaybackPromptFence {
    private var version = 0L

    class Ticket internal constructor(internal val version: Long, internal val url: String?)

    fun capture(url: String?): Ticket = Ticket(version, url)

    fun invalidate() {
        version += 1
    }

    fun accepts(ticket: Ticket, currentUrl: String?, attached: Boolean): Boolean {
        return attached && ticket.version == version && ticket.url == currentUrl
    }
}
