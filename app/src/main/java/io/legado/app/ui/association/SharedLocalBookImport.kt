package io.legado.app.ui.association

import android.net.Uri
import io.legado.app.data.association.collectAssociationImportFiles
import io.legado.app.data.association.copyAssociationLocalBook
import io.legado.app.data.association.previewAssociationLocalBooks
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File

/** Compatibility adapters for existing import callers; the IO algorithm lives in data. */
internal fun collectSharedImportFiles(uris: List<Uri>, staging: File): List<File> =
    collectAssociationImportFiles(uris, staging)

internal fun previewSharedLocalBooks(files: List<File>): List<ImportBook> =
    previewAssociationLocalBooks(files).map { preview ->
        ImportBook(
            FileDoc.fromUri(Uri.parse(preview.fileUri), preview.isDirectory),
            preview.onBookshelf,
            GSON.fromJsonObject<Book>(preview.bookJson).getOrThrow(),
        )
    }

internal fun copySharedLocalBook(file: FileDoc, directory: Uri): Uri =
    copyAssociationLocalBook(file, directory)
