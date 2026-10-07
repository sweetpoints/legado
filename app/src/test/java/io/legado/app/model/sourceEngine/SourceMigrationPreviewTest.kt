package io.legado.app.model.sourceEngine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMigrationPreviewTest {
    private fun report() =
        mutableMapOf<String, Any?>(
            "protocolVersion" to 1,
            "reportVersion" to 1,
            "verified" to false,
            "executed" to false,
            "status" to "unverified",
            "requiresManualWork" to false,
            "issues" to emptyList<Any>(),
            "candidate" to mapOf("schemaVersion" to 1),
        )

    @Test
    fun unverifiedProposalCanBeAppliedButManualProposalCannot() {
        assertTrue(SourceMigrationPreview.fromChannel(report()).canApply)
        val manual =
            report().apply {
                put("status", "manualRequired")
                put("requiresManualWork", true)
                put(
                    "issues",
                    listOf(
                        mapOf(
                            "path" to "ruleSearch",
                            "code" to "unsupported",
                            "message" to "Review required",
                        )
                    ),
                )
            }
        assertFalse(SourceMigrationPreview.fromChannel(manual).canApply)
    }

    @Test
    fun inconsistentOrVerifiedResponsesAreRejected() {
        for ((key, value) in
            listOf(
                "verified" to true,
                "executed" to true,
                "requiresManualWork" to true,
                "status" to "verified",
                "protocolVersion" to 2,
            )) {
            assertTrue(
                runCatching {
                        SourceMigrationPreview.fromChannel(report().apply { put(key, value) })
                    }
                    .isFailure
            )
        }
    }

    @Test
    fun missingCandidateAndMalformedIssuesAreRejected() {
        assertTrue(
            runCatching {
                    SourceMigrationPreview.fromChannel(report().apply { remove("candidate") })
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    SourceMigrationPreview.fromChannel(
                        report().apply { put("issues", listOf(mapOf("code" to "unsupported"))) }
                    )
                }
                .isFailure
        )
    }
}
