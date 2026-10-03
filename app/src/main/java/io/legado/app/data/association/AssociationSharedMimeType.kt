package io.legado.app.data.association

fun associationSupportedSharedImportMimeType(mimeType: String?): Boolean =
    mimeType.equals("text/plain", ignoreCase = true) ||
        mimeType.equals("text/*", ignoreCase = true) ||
        mimeType.equals("application/json", ignoreCase = true) ||
        mimeType.equals("application/epub+zip", ignoreCase = true) ||
        mimeType.equals("application/pdf", ignoreCase = true) ||
        mimeType.equals("application/zip", ignoreCase = true) ||
        mimeType.equals("application/x-zip-compressed", ignoreCase = true) ||
        mimeType.equals("application/x-rar-compressed", ignoreCase = true) ||
        mimeType.equals("application/vnd.rar", ignoreCase = true) ||
        mimeType.equals("application/x-7z-compressed", ignoreCase = true) ||
        mimeType.equals("application/mobi", ignoreCase = true) ||
        mimeType.equals("application/x-mobipocket-ebook", ignoreCase = true) ||
        mimeType.equals("application/azw", ignoreCase = true) ||
        mimeType.equals("application/azw3", ignoreCase = true) ||
        mimeType.equals("application/x-mobi8-ebook", ignoreCase = true) ||
        mimeType.equals("application/octet-stream", ignoreCase = true)
