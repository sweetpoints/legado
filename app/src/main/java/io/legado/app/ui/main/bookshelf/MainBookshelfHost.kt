package io.legado.app.ui.main.bookshelf

import io.legado.app.data.preferences.BookshelfSettingsEffects
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputResult

/** Activity contract used by the remaining Compose bookshelf dialogs. */
internal interface MainBookshelfHost {
    val bookshelfTransferModel: BookshelfViewModel

    fun submitShelfInput(kind: Int, result: BookshelfInputResult)

    fun selectBookshelfImportFile(groupId: Long)

    fun applySettingsEffects(effects: BookshelfSettingsEffects)

    fun acceptLegacyImportResult(
        owner: BaseBookshelfFragment,
        requestId: String,
        uri: String?,
        groupId: Long?,
    )

    fun acceptLegacyExportResult(
        owner: BaseBookshelfFragment,
        requestId: String,
        path: String?,
        uri: String?,
    )

    fun finishLegacyResultBridge(owner: BaseBookshelfFragment)
}
