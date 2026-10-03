package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ReplaceRulePreparedImportHostTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = AppReplaceRuleImportRepository(context)
    private val owned = mutableListOf<String>()

    @After fun after() = runBlocking { owned.forEach { repository.release(it) } }

    @Test
    fun actualParserPrivateSessionAndDialogArgumentsRestoreLargeRuleWithoutRawBundle() =
        runBlocking {
            val json =
                GSON.toJson(
                    listOf(
                        ReplaceRule(
                            name = "Rule-${UUID.randomUUID()}",
                            pattern = "Q".repeat(2000000),
                            replacement = "Exact",
                        )
                    )
                )
            val id =
                AppReplaceRulePreparedImportRepository(repository).prepare(json).also {
                    owned += it
                }
            val dialog = ImportReplaceRuleDialog.prepared(id)
            assertEquals(setOf("preparedSession"), dialog.requireArguments().keySet())
            assertEquals(id, dialog.requireArguments().getString("preparedSession"))
            assertFalse(dialog.requireArguments().containsKey("source"))
            assertEquals(json.length > 2000000, true)
            val session = AppReplaceRuleImportRepository(context).restore(id)!!
            assertTrue(session.items.single().json.contains("Q".repeat(2000000)))
            val model =
                withContext(Dispatchers.Main) {
                    ImportReplaceRuleViewModel(repository, SavedStateHandle(), "", id)
                }
            withTimeout(10000) { model.state.firstReady() }
            assertEquals(session.items, model.state.value.items)
            withContext(Dispatchers.Main) { model.cancel() }
        }

    @Test
    fun exactUuidReleaseRemovesAtomicSideFilesAndPreservesOtherOwnedSession() = runBlocking {
        val bridge = AppReplaceRulePreparedImportRepository(repository)
        val source =
            GSON.toJson(listOf(ReplaceRule(name = "Owned-${UUID.randomUUID()}", pattern = "Exact")))
        val first = bridge.prepare(source).also { owned += it }
        val second = bridge.prepare(source).also { owned += it }
        bridge.release(first)
        assertNull(repository.restore(first))
        assertNotNull(repository.restore(second))
        val path = File(context.cacheDir, "replace-rule-import/$first.json")
        assertTrue(
            listOf(path, File(path.path + ".bak"), File(path.path + ".new")).none { it.exists() }
        )
    }

    private suspend fun kotlinx.coroutines.flow.StateFlow<ImportReplaceRuleState>.firstReady() {
        first { !it.loading }
            .also {
                assertNull(it.error)
                assertFalse(it.finished)
            }
    }
}
