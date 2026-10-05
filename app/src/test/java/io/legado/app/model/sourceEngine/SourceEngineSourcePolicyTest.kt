package io.legado.app.model.sourceEngine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineSourcePolicyTest {
    private val candidate =
        """{"schemaVersion":1,"id":"source","name":"Source","baseUrl":"https://example.org"}"""

    @Test
    fun candidateApplyReplacesReservedLinesAndPreservesUserCommentBytes() {
        val comments = " notes \r\n用户说明\r\n"
        val old = "@engine:legacy\n@source:v1 {\"schemaVersion\":1}\n@engine:dart\n$comments"
        val updated = SourceEngineSourcePolicy.withCandidate(old, candidate)
        assertEquals("@source:v1 $candidate\n$comments", updated)
        assertEquals(updated, SourceEngineSourcePolicy.withCandidate(updated, candidate))
        assertTrue(SourceEngineSourcePolicy.hasVersionedDefinition(updated))
    }

    @Test
    fun ordinaryMarkerMentionsArePreservedAndAreNotDefinitions() {
        val comment = "notes mention @engine:dart and @source:v1 in prose"
        assertFalse(SourceEngineSourcePolicy.hasVersionedDefinition(comment))
        assertEquals(
            "@source:v1 $candidate\n$comment",
            SourceEngineSourcePolicy.withCandidate(comment, candidate),
        )
    }

    @Test
    fun multilineCandidateIsSerializedToOneRoutingLine() {
        val updated = SourceEngineSourcePolicy.withCandidate("notes", "{\n\"schemaVersion\": 1\n}")
        assertEquals("@source:v1 {\"schemaVersion\":1}\nnotes", updated)
    }

    @Test
    fun candidateRequiresStrictJsonObjectAndVersionOne() {
        for (invalid in
            listOf(
                "{schemaVersion:1}",
                "[]",
                "null",
                "{}",
                "{\"schemaVersion\":2}",
                "{\"schemaVersion\":1.5}",
                "{\"schemaVersion\":\"1\"}",
                "{\"schemaVersion\":1} trailing",
            )) {
            assertThrows(RuntimeException::class.java) {
                SourceEngineSourcePolicy.withCandidate("notes", invalid)
            }
        }
    }
}
