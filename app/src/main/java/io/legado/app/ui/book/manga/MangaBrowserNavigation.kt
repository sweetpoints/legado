package io.legado.app.ui.book.manga

import android.content.Context
import android.content.Intent
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.help.source.getSourceType
import io.legado.app.ui.browser.WebViewActivity

/** Browser source type identifies BookSource versus RSS, not the source's image/text category. */
internal fun mangaBrowserSourceKind(source: BookSource?): Int? = source?.getSourceType()

/** Preserve the existing public browser ABI and complete opaque rule/header URLs. */
internal fun mangaChapterBrowserIntent(context: Context, request: MangaNativeRequest): Intent =
    Intent(context, WebViewActivity::class.java).apply {
        putExtra("title", request.title)
        putExtra("url", request.imageUrl)
        putExtra("sourceOrigin", request.sourceOrigin)
        putExtra("sourceName", request.sourceName)
        putExtra("sourceType", request.sourceType ?: 0)
    }
