package io.legado.app.model.sourceEngine

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.Strictness

/** Replaces migration payloads without changing ordinary user comments. */
object SourceEngineSourcePolicy {
    private val json = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val engineLines =
        Regex("(?m)^[^\\S\\r\\n]*@engine:(?:dart|legacy)[^\\S\\r\\n]*(?:\\r?\\n|$)")
    private val candidateLines = Regex("(?m)^[^\\S\\r\\n]*@source:v1 [^\\r\\n]*(?:\\r?\\n|$)")

    fun hasVersionedDefinition(comment: String?): Boolean =
        comment.orEmpty().lineSequence().any { it.trim().startsWith("@source:v1 ") }

    /** The host validates the full schema before apply; this boundary requires a v1 JSON object. */
    fun withCandidate(comment: String?, candidateJson: String): String {
        val candidate = json.fromJson(candidateJson, JsonElement::class.java)
        require(candidate != null) { "Candidate must be a source JSON object" }
        require(candidate.isJsonObject) { "Candidate must be a source JSON object" }
        val version = candidate.asJsonObject.get("schemaVersion")
        require(
            version?.isJsonPrimitive == true &&
                version.asJsonPrimitive.isNumber &&
                version.asString == "1"
        ) {
            "Candidate must use source schemaVersion 1"
        }
        val retained = candidateLines.replace(engineLines.replace(comment.orEmpty(), ""), "")
        return prepend("@source:v1 $candidate", retained)
    }

    private fun prepend(marker: String, retained: String): String =
        if (retained.isEmpty()) marker else "$marker\n$retained"
}
