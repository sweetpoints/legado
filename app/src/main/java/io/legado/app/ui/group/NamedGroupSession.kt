package io.legado.app.ui.group

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal interface NamedGroupDraftSession {
    fun restore(): NamedGroupState?
    fun save(state: NamedGroupState)
    fun clear()
}

/** Saved instance state carries only an opaque token; private input stays in the owned file. */
internal class NamedGroupSession(context: Context, saved: SavedStateHandle) : NamedGroupDraftSession {
    private val existing = saved.get<String>("namedGroup.session")?.matches(Regex("[a-f0-9-]{36}")) == true
    private val token = saved.get<String>("namedGroup.session")?.takeIf {
        it.matches(Regex("[a-f0-9-]{36}"))
    } ?: UUID.randomUUID().toString().also { saved["namedGroup.session"] = it }
    private val file = File(context.filesDir, "named-group-sessions/$token.json")
    override fun restore(): NamedGroupState? {
        if (!file.exists()) { check(!existing) { "Group draft file is missing; retry recovery" }; return null }
        val json = JSONObject(file.readText())
        val name = json.getString("name")
        val start = json.getInt("start"); val end = json.getInt("end")
        require(start in 0..name.length && end in 0..name.length) { "Invalid group draft selection" }
        return NamedGroupState(editing = json.getBoolean("editing"),
            original = if (json.isNull("original")) null else json.getString("original"),
            name = name, selectionStart = start,
            selectionEnd = end,
            mutationUncertain = json.optBoolean("mutationUncertain"),
            error = if (json.optBoolean("mutationUncertain")) "Previous group write may have completed; inspect groups and cancel this draft before editing again" else null)
    }
    override fun save(state: NamedGroupState) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".pending")
        temporary.writeText(JSONObject().put("editing", state.editing)
            .put("original", state.original ?: JSONObject.NULL).put("name", state.name)
            .put("mutationUncertain", state.mutationUncertain).put("start", state.selectionStart).put("end", state.selectionEnd).toString())
        check(temporary.renameTo(file)) { "Cannot persist group draft" }
    }
    override fun clear() { if (file.exists()) check(file.delete()) { "Cannot clear group draft" } }
}
