package io.legado.app.ui.main.rss

import io.legado.app.data.repository.MainRssDestination
import io.legado.app.data.repository.MainRssPrepared
import io.legado.app.data.repository.RssReaderLaunchRepository
import io.legado.app.data.repository.RssReaderRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The native reader takes ownership only after the exact pending action is acknowledged. */
internal suspend fun deliverMainRssRequest(
    launches: RssReaderLaunchRepository,
    resolve: suspend () -> MainRssPrepared?,
    ready: () -> Boolean,
    acknowledge: () -> Boolean,
    missing: () -> Unit,
    native: (MainRssPrepared, String?) -> Unit,
) {
    var ownedTicket: String? = null
    try {
        val request = resolve()
        currentCoroutineContext().ensureActive()
        if (!ready()) return
        val navigation = request?.navigation
        val reader =
            if (request?.action == MainRssAction.Open.name)
                when (navigation?.destination) {
                    MainRssDestination.ReaderLink ->
                        RssReaderRequest(
                            origin = navigation.sourceUrl,
                            title = navigation.sourceName,
                            openUrl = navigation.value,
                        )
                    MainRssDestination.ReaderHtml ->
                        RssReaderRequest(
                            origin = navigation.sourceUrl,
                            title = navigation.sourceName,
                            startHtml = navigation.value,
                        )
                    else -> null
                }
            else null
        if (reader != null) ownedTicket = launches.stage(reader)
        currentCoroutineContext().ensureActive()
        if (!ready() || !acknowledge()) return
        if (request == null) missing()
        else {
            native(request, ownedTicket)
            ownedTicket = null
        }
    } finally {
        ownedTicket?.let { ticket -> withContext(NonCancellable) { launches.release(ticket) } }
    }
}
