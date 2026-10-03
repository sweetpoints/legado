package io.legado.app.model.remote

/** Full URIs and archive names belong to the private draft, never to a saved Bundle. */
internal sealed interface RemoteLibraryReadTarget {
    data object None : RemoteLibraryReadTarget

    data class Open(val bookId: String) : RemoteLibraryReadTarget

    data class DownloadArchive(val entry: RemoteLibraryEntry) : RemoteLibraryReadTarget

    data class ChooseArchive(val uri: String, val names: List<String>) : RemoteLibraryReadTarget

    data class ImportArchive(val uri: String, val name: String) : RemoteLibraryReadTarget

    data object UnsupportedArchive : RemoteLibraryReadTarget
}
