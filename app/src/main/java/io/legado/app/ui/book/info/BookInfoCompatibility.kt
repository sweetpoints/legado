package io.legado.app.ui.book.info

import io.legado.app.data.repository.normalizeBookDetailWebFileName

/** Retains the original helper API for callers while normalization lives with web-file data. */
internal fun normalizeWebFileName(
    fileName: String,
    rawSuffix: String?,
    replaceExistingSuffix: Boolean = true,
): String = normalizeBookDetailWebFileName(fileName, rawSuffix, replaceExistingSuffix)
