package io.legado.app.data.repository

import io.legado.app.help.HighlightStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightRuleEditorRepositoryTest {
    @Test
    fun existingRulePreservesEveryHiddenAndEditableFieldAndRestorationUsesDraftRatherThanReloadingRoom() =
        runTest {
            val store = Fake()
            val repo =
                DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
            val loaded = repo.initial("s", 9, null)
            assertEquals(store.entity, loaded.rule)
            val edited =
                loaded.copy(
                    rule = loaded.rule.copy(pattern = "new", style = HighlightStyle(bold = true)),
                    revision = 4,
                )
            repo.draft("s", edited)
            store.entity = null
            assertEquals(edited, repo.initial("s", 9, null))
            assertEquals(1, store.loads)
        }

    @Test
    fun initialSeedIsRetainedUntilSuccessfulDiskWriteAndRetryDoesNotReplaceLargePattern() =
        runTest {
            val store =
                Fake().apply {
                    writesFail = true
                    seed = HighlightRuleDraft(pattern = "x".repeat(1200000))
                }
            val repo =
                DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
            assertTrue(runCatching { repo.initial("s", -1, "seed") }.isFailure)
            assertFalse(store.released)
            store.writesFail = false
            val loaded = repo.initial("s", -1, "seed")
            assertEquals(1200000, loaded.rule.pattern.length)
            assertTrue(store.released)
            assertEquals(loaded, repo.initial("s", -1, "seed"))
        }

    @Test
    fun validationMatchesLiteralAndRegexPolicyAndNeverWritesRoomForInvalidPattern() = runTest {
        val store = Fake()
        val repo =
            DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
        for (rule in
            listOf(
                HighlightRuleDraft(pattern = ""),
                HighlightRuleDraft(pattern = "[", isRegex = true),
            )) {
            assertTrue(
                runCatching { repo.save("s", HighlightRuleEditorDraft(rule)) }.exceptionOrNull()
                    is InvalidHighlightRuleException
            )
        }
        assertTrue(store.inserted.isEmpty())
        repo.save("s", HighlightRuleEditorDraft(HighlightRuleDraft(pattern = " ", isRegex = false)))
        assertEquals(" ", store.inserted.single().pattern)
    }

    @Test
    fun saveNormalizesGroupAndBlankScopeButKeepsNonBlankScopeAndHiddenMetadata() = runTest {
        val store = Fake()
        val repo =
            DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
        val rule =
            store.entity!!.copy(
                scope = " book ",
                group = " Group ",
                pattern = "new",
                style = HighlightStyle(fontSize = 900f),
            )
        val result = repo.save("s", HighlightRuleEditorDraft(rule, 10))
        assertEquals("Group", store.inserted.single().group)
        assertEquals(" book ", store.inserted.single().scope)
        assertEquals(9L, result.savedRuleId)
        assertEquals(11L, result.revision)
        assertFalse(result.rule.isEnabled)
        assertEquals(77, result.rule.order)
        assertEquals(1234L, result.rule.timeoutMillisecond)
        assertTrue(result.rule.applyToTitle)
        assertFalse(result.rule.applyToBody)
        assertEquals(rule.uuid, result.rule.uuid)
        val next = repo.save("s", result)
        assertEquals(result, next)
        assertEquals(1, store.inserted.size)
    }

    @Test
    fun missingRuleDoesNotCreateReplacementAndBlankScopeAndGroupBecomeNull() = runTest {
        val store = Fake().apply { entity = null }
        val repo =
            DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(
            runCatching { repo.initial("s", 99, null) }.exceptionOrNull()
                is MissingHighlightRuleException
        )
        assertNull(store.stored)
        repo.save(
            "s",
            HighlightRuleEditorDraft(
                HighlightRuleDraft(pattern = "literal", scope = "  ", group = " ")
            ),
        )
        assertNull(store.inserted.single().scope)
        assertNull(store.inserted.single().group)
    }

    @Test
    fun initialReadFailureCannotAccidentallyWriteNewRuleOrReleaseSeed() = runTest {
        val store = Fake().apply { readsFail = true }
        val repo =
            DefaultHighlightRuleEditorRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { repo.initial("s", -1, "key") }.isFailure)
        assertFalse(store.released)
        assertTrue(store.inserted.isEmpty())
        assertNull(store.stored)
    }

    private class Fake : HighlightRuleEditorStore {
        var entity: HighlightRuleDraft? =
            HighlightRuleDraft(
                id = 9,
                name = "Old",
                pattern = "old",
                scope = "book",
                isEnabled = false,
                style = HighlightStyle(fill = 123),
                order = 77,
                timeoutMillisecond = 1234,
                group = "Original",
                applyToTitle = true,
                applyToBody = false,
            )
        var seed = HighlightRuleDraft()
        var stored: HighlightRuleEditorDraft? = null
        var loads = 0
        var released = false
        var writesFail = false
        var readsFail = false
        val inserted = mutableListOf<HighlightRuleDraft>()

        override suspend fun load(id: Long): HighlightRuleDraft? {
            loads++
            return entity
        }

        override suspend fun seed(key: String?) = seed

        override suspend fun read(session: String): HighlightRuleEditorDraft? {
            if (readsFail) error("read")
            return stored
        }

        override suspend fun write(session: String, draft: HighlightRuleEditorDraft) {
            if (writesFail) error("write")
            stored = draft
        }

        override suspend fun insert(rule: HighlightRuleDraft): Long {
            inserted += rule
            return if (rule.id > 0) rule.id else 10
        }

        override fun releaseSeed(key: String?) {
            released = true
        }
    }
}
