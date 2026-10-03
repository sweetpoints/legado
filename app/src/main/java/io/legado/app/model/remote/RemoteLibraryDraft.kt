package io.legado.app.model.remote

import androidx.annotation.Keep

@Keep
internal enum class RemoteLibraryPrompt {
    StorageHelp,
    Reimport,
    DownloadArchive,
    ImportArchive,
    ChooseArchive,
}

@Keep
internal data class RemoteLibraryConfirmation(
    val kind: RemoteLibraryPrompt,
    val entryId: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val names: List<String> = emptyList(),
    val help: String = "",
)

@Keep
internal enum class RemoteLibraryTaskKind {
    ImportBooks,
    ImportArchive,
}

@Keep
internal data class RemoteLibraryTask(
    val id: String,
    val kind: RemoteLibraryTaskKind,
    val ids: List<String> = emptyList(),
    val uri: String? = null,
    val name: String? = null,
    val readAfter: String? = null,
    val completed: List<String> = emptyList(),
)

@Keep
internal enum class RemoteLibraryEffect {
    PickStorage,
    OpenBook,
    Log,
    Help,
    Servers,
    Toast,
    Close,
}

@Keep
internal data class RemoteLibraryReceipt(
    val id: String,
    val effect: RemoteLibraryEffect,
    val text: String? = null,
    val bookId: String? = null,
    val resource: Int = 0,
)

/**
 * Full paths and accepted task receipts belong to disk; SavedState retains only opaque ownership.
 */
@Keep
internal data class RemoteLibraryDraft(
    val revision: Long = 0,
    val query: String = "",
    val sort: RemoteLibrarySort = RemoteLibrarySort.Modified,
    val ascending: Boolean = false,
    val directories: List<RemoteLibraryEntry> = emptyList(),
    val rows: List<RemoteLibraryEntry> = emptyList(),
    val selected: List<String> = emptyList(),
    val confirmation: RemoteLibraryConfirmation? = null,
    val task: RemoteLibraryTask? = null,
    val effects: List<RemoteLibraryReceipt> = emptyList(),
    val storageTicket: String? = null,
    val initialHelpChecked: Boolean = false,
)
