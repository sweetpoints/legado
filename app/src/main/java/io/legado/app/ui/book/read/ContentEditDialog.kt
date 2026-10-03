package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.BookContentEditorRepository
import io.legado.app.data.repository.ContentEditorTarget
import io.legado.app.model.ReadBook
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout

/** Captures the reader target when opened; navigating the reader cannot retarget a save. */
class ContentEditDialog : BaseComposeDialogFragment() {
    companion object {
        fun newInstance(): ContentEditDialog? {
            val book = ReadBook.book ?: return null
            val index = ReadBook.durChapterIndex
            val title =
                ReadBook.curTextChapter
                    ?.takeIf { it.chapter.bookUrl == book.bookUrl && it.chapter.index == index }
                    ?.title ?: book.durChapterTitle?.takeIf { book.durChapterIndex == index }
            return ContentEditDialog().apply {
                arguments =
                    Bundle().apply {
                        putString("bookUrl", book.bookUrl)
                        putInt("chapterIndex", index)
                        putInt("chapterPos", ReadBook.durChapterPos)
                        putString("title", title)
                    }
            }
        }
    }

    private val target by
        lazy(LazyThreadSafetyMode.NONE) {
            ContentEditorTarget(
                arguments?.getString("bookUrl").orEmpty(),
                arguments?.getInt("chapterIndex") ?: 0,
                arguments?.getInt("chapterPos") ?: 0,
            )
        }
    internal val viewModel by
        viewModels<ContentEditorViewModel> {
            viewModelFactory {
                initializer {
                    ContentEditorViewModel(
                        BookContentEditorRepository(requireContext()),
                        createSavedStateHandle(),
                        target,
                        arguments?.getString("title"),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Native Dialog cancellation happens after dismissal. Intercept back to await auto-save.
        isCancelable = false
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) viewModel.close()
                true
            } else false
        }
    }

    @Composable
    override fun Content() {
        ContentEditorRoute(
            viewModel,
            { requireContext().sendToClip(it) },
            {
                if (target.matches(ReadBook.book?.bookUrl, ReadBook.durChapterIndex))
                    ReadBook.loadContent(target.chapterIndex, resetPageOffset = false)
            },
            ::dismissAllowingStateLoss,
        )
    }
}
