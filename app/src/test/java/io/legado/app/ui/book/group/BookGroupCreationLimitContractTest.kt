package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookGroupEditorRepository
import io.legado.app.data.repository.BookGroupEditorSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class BookGroupCreationLimitContractTest {

    @Test
    fun `only 63 positive group ids can be created`() {
        val daoSource =
            projectFile("src/main/java/io/legado/app/data/dao/BookGroupDao.kt").readText()

        assertTrue(daoSource.contains("select count(*) < 63 from book_groups where groupId > 0"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editorAtGroupLimitRetainsDraftAndDoesNotClose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository =
                object : BookGroupEditorRepository {
                    override suspend fun load(id: Long): BookGroupEditorSnapshot? = null

                    override suspend fun save(
                        draft: BookGroupEditorSnapshot,
                        existing: Boolean,
                    ): BookGroupEditorSnapshot = error("分组已达上限(63个)")

                    override suspend fun delete(id: Long) = Unit

                    override suspend fun importCover(uri: String) = uri
                }
            val model = BookGroupEditorViewModel(repository, SavedStateHandle())
            model.name("draft")
            model.sort(5)
            model.save()
            runCurrent()
            assertEquals("分组已达上限(63个)", model.state.value.error)
            assertEquals("draft", model.state.value.draft.name)
            assertEquals(5, model.state.value.draft.bookSort)
            assertFalse(model.state.value.finished)
            assertFalse(model.state.value.saving)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
