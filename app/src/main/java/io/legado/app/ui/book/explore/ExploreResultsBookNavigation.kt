package io.legado.app.ui.book.explore

import android.content.Context
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.ExploreResultsRow
import io.legado.app.ui.book.info.BookInfoNavigation

/** Uses an application context; no Activity or callback survives in the ViewModel. */
class AppExploreResultsBookNavigation(context: Context) : ExploreResultsBookNavigation {
    private val application = context.applicationContext

    override suspend fun prepare(row: ExploreResultsRow): String =
        BookInfoNavigation.prepare(
            application,
            BookDetailIdentity(row.name, row.author, row.bookUrl),
        )

    override suspend fun abandon(ticket: String) {
        BookInfoNavigation.abandon(application, ticket)
    }
}
