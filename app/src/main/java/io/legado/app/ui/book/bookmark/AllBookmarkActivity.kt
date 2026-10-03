package io.legado.app.ui.book.bookmark

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AllBookmarksDestination
import io.legado.app.data.repository.AppAllBookmarksRepository
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

class AllBookmarkActivity : BaseComposeActivity() {
    internal val model by
        viewModels<AllBookmarksViewModel> {
            viewModelFactory {
                initializer {
                    AllBookmarksViewModel(AppAllBookmarksRepository(), createSavedStateHandle())
                }
            }
        }
    private val exportDir =
        registerForActivityResult(HandleFileContract()) { result ->
            model.directoryResult(result.uri?.toString())
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        AllBookmarksRoute(
            model,
            { !isFinishing && !supportFragmentManager.isStateSaved },
            ::finish,
            ::open,
            { markdown -> exportDir.launch { requestCode = if (markdown) 2 else 1 } },
            { toastOnUi(it) },
        )
    }

    private fun open(destination: AllBookmarksDestination, position: Int) {
        val book = destination.book
        if (book == null)
            BookmarkDialog(destination.bookmark, position)
                .show(supportFragmentManager, "all-bookmark-editor")
        else
            startActivityForBook(book) {
                putExtra("index", destination.bookmark.chapterIndex)
                putExtra("chapterPos", destination.bookmark.chapterPos)
            }
    }
}
