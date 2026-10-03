package io.legado.app.model.rss

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/** Apply parser changes only to keys whose baseline is still current; preserve concurrent edits. */
fun mergeRssReaderVariables(baseline: String?, parsed: String?, current: String?): String? {
    if (baseline == parsed) return current
    fun values(json: String?): Map<String, String> =
        GSON.fromJsonObject<HashMap<String, String>>(json).getOrNull().orEmpty()
    val before = values(baseline)
    val after = values(parsed)
    val latest = values(current)
    val merged = LinkedHashMap(latest)
    var changed = false
    (before.keys + after.keys).forEach { key ->
        if (before.containsKey(key) == after.containsKey(key) && before[key] == after[key])
            return@forEach
        if (latest.containsKey(key) != before.containsKey(key) || latest[key] != before[key])
            return@forEach
        if (after.containsKey(key)) merged[key] = after.getValue(key) else merged.remove(key)
        changed = true
    }
    return if (changed) GSON.toJson(merged) else current
}
