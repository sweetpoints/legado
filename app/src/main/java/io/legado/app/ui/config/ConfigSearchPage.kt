package io.legado.app.ui.config

/** Compose settings pages expose search without depending on Android Preference objects. */
internal interface ConfigSearchPage {
    fun searchSettings(query: String, onSelected: () -> Unit)
}
