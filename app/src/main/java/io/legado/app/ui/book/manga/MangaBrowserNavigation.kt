package io.legado.app.ui.book.manga

import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.help.source.getSourceType
import io.legado.app.model.browser.BrowserRequest

/** Browser source type identifies BookSource versus RSS, not the source's image/text category. */
internal fun mangaBrowserSourceKind(source: BookSource?): Int? = source?.getSourceType()

/** Preserve manga's complete opaque rule/header URL in the browser's private prepared payload. */
internal fun mangaChapterBrowserRequest(request: MangaNativeRequest) =
    BrowserRequest(
        url = checkNotNull(request.imageUrl),
        title = request.title.orEmpty(),
        sourceOrigin = request.sourceOrigin.orEmpty(),
        sourceName = request.sourceName.orEmpty(),
        sourceType = request.sourceType ?: 0,
    )
