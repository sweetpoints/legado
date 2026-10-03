package io.legado.app.ui.navigation

/** Stable identity; never persist a position in the configurable bottom bar. */
enum class MainDestination(val key: String, val legacyId: Int, val skinSlot: String) {
    Bookshelf("bookshelf", 0, "bookshelf"),
    Explore("explore", 1, "home"),
    Rss("rss", 2, "notes"),
    My("my", 3, "settings");

    companion object {
        fun fromKey(key: String?): MainDestination =
            entries.firstOrNull { it.key == key } ?: Bookshelf
    }
}
