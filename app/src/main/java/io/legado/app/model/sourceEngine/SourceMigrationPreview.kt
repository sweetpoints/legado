package io.legado.app.model.sourceEngine

import io.legado.app.utils.GSON

data class SourceMigrationIssue(val path: String, val code: String, val message: String)

/** An offline proposal. It never establishes that a source has successfully run. */
data class SourceMigrationPreview(
    val issues: List<SourceMigrationIssue>,
    val candidateJson: String?,
    val requiresManualWork: Boolean,
    val status: String,
) {
    val canApply: Boolean
        get() =
            candidateJson != null &&
                !requiresManualWork &&
                issues.isEmpty() &&
                status == "unverified"

    companion object {
        fun fromChannel(value: Any?): SourceMigrationPreview {
            require(value is Map<*, *>) { "Invalid migration response" }
            require(value["protocolVersion"] == 1 && value["reportVersion"] == 1) {
                "Unsupported migration response version"
            }
            require(value["verified"] == false && value["executed"] == false) {
                "Migration response must remain unverified and unexecuted"
            }
            val status = value["status"]
            require(status == "unverified" || status == "manualRequired") {
                "Invalid migration status"
            }
            val rawIssues = value["issues"]
            require(rawIssues is List<*>) { "Invalid migration issues" }
            val issues = rawIssues.map {
                require(it is Map<*, *>) { "Invalid migration issue" }
                val path = it["path"]
                val code = it["code"]
                val message = it["message"]
                require(path is String && code is String && message is String) {
                    "Invalid migration issue fields"
                }
                SourceMigrationIssue(path, code, message)
            }
            val manual = value["requiresManualWork"]
            require(
                manual is Boolean &&
                    manual == issues.isNotEmpty() &&
                    manual == (status == "manualRequired")
            ) {
                "Inconsistent migration status"
            }
            val candidate = value["candidate"]
            require(candidate is Map<*, *> && candidate["schemaVersion"] == 1) {
                "Invalid migration candidate"
            }
            return SourceMigrationPreview(issues, GSON.toJson(candidate), manual, status as String)
        }
    }
}
