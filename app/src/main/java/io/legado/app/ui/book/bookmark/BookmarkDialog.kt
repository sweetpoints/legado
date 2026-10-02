package io.legado.app.ui.book.bookmark

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.utils.setLayout
import kotlinx.coroutines.launch
import splitties.init.appCtx

class BookmarkDialog() : BaseComposeDialogFragment() {
    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = request(BookmarkEditorSeed.from(bookmark, editPos))
    }
    private fun request(seed: BookmarkEditorSeed) = Bundle().apply {
        putString("requestId", FileBookmarkEditorRepository.stage(appCtx, seed))
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = arguments
        if (legacy?.containsKey("requestId") != true) {
            val bookmark = legacy?.getParcelable<Bookmark>("bookmark")
            val seed = bookmark?.let { BookmarkEditorSeed.from(it, legacy?.getInt("editPos", -1) ?: -1) }
                ?: BookmarkEditorSeed(0, "", "", 0, 0, "", "", "")
            arguments = request(seed).apply { putBoolean("noData", bookmark == null) }
        }
    }
    internal val model by viewModels<BookmarkEditorViewModel> {
        viewModelFactory { initializer { BookmarkEditorViewModel(FileBookmarkEditorRepository(requireContext()),
            createSavedStateHandle(), requireArguments().getString("requestId")!!) } }
    }
    override fun onStart() {
        super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        requireView().setBackgroundColor(Color.TRANSPARENT)
        if (arguments?.getBoolean("noData") == true) model.cancel()
    }
    override fun onStop() { lifecycleScope.launch { runCatching { model.flushDraft() } }; super.onStop() }
    @Composable override fun Content() {
        BookmarkEditorRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, ::dismissAllowingStateLoss, { isCancelable = it })
    }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
}
