package io.legado.app.model

import androidx.collection.LruCache
import io.legado.app.utils.InfoMap

/** Shared discovery script state belongs to the model, independent of a page adapter. */
object ExploreInfoMapStore {
    val exploreInfoMapList = LruCache<String, InfoMap>(99)
}
