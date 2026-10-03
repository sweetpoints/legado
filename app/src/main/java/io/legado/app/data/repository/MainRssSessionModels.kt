package io.legado.app.data.repository

import androidx.annotation.Keep

/** Gson-backed process recreation data. Keep the complete reflected graph and JSON names stable. */
@Keep
data class MainRssCheckpoint(
    val revision: Long = 0,
    val query: String = "",
    val queryStart: Int = 0,
    val queryEnd: Int = 0,
    val deletingId: String? = null,
    val deletingName: String? = null,
    val pending: MainRssPrepared? = null,
)

@Keep
data class MainRssPrepared(
    val action: String,
    val nonce: String,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val navigation: MainRssNavigation? = null,
)

@Keep
data class MainRssNavigation(
    val destination: MainRssDestination,
    val sourceUrl: String,
    val sourceName: String,
    val value: String? = null,
)

@Keep
enum class MainRssDestination {
    Categories,
    ReaderLink,
    ReaderHtml,
    External,
}
