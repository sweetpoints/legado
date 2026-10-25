package io.legado.app.ui.book.bookmark

import io.legado.app.utils.resizeForIme

import android.content.DialogInterface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.utils.setLayout
import kotlinx.coroutines.launch
import splitties.init.appCtx

class BookmarkDialog() : BaseDialogFragment(0) {
    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = request(BookmarkEditorSeed.from(bookmark, editPos))
    }

    private fun request(seed: BookmarkEditorSeed) =
        Bundle().apply {
            putString("requestId", FileBookmarkEditorRepository.stage(appCtx, seed))
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        isCancelable = false
        val legacy = arguments
        if (legacy?.containsKey("requestId") != true) {
            val bookmark = legacy?.getParcelable<Bookmark>("bookmark")
            val seed =
                bookmark?.let { BookmarkEditorSeed.from(it, legacy.getInt("editPos", -1)) }
                    ?: BookmarkEditorSeed(0, "", "", 0, 0, "", "", "")
            arguments = request(seed).apply { putBoolean("noData", bookmark == null) }
        }
    }

    internal val model by
        viewModels<BookmarkEditorViewModel> {
            viewModelFactory {
                initializer {
                    BookmarkEditorViewModel(
                        FileBookmarkEditorRepository(requireContext()),
                        createSavedStateHandle(),
                        requireArguments().getString("requestId")!!,
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        // Keep this floating window non-fullscreen and native-frame-fitted, like NamedGroupDialog.
        // BookmarkEditorPage consumes the already fitted bottom inset at its own boundary.
        // A future fullscreen/edge-to-edge host must change both sides of this IME contract.
        dialog?.window?.resizeForIme()
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) requestBackFromHost()
                true
            } else false
        }
    }

    internal val coordinator by lazy {
        BookmarkEditorNativeCoordinator(model, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved },
            ::dismissAllowingStateLoss)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.bookmark_editor_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        coordinator
        if (arguments?.getBoolean("noData") == true) model.cancel()
        if (childFragmentManager.findFragmentByTag("bookmark-ui") == null)
            childFragmentManager.beginTransaction().add(R.id.bookmark_editor_flutter_content,
                BookmarkEditorFlutterFragment(), "bookmark-ui").commitNow()
    }
    override fun onResume() {
        super.onResume()
        view?.post { if (isResumed) {
            (childFragmentManager.findFragmentByTag("bookmark-ui") as? BookmarkEditorFlutterFragment)?.onPostResume()
            coordinator.onHostReady()
        } }
    }
    internal fun requestBackFromHost() {
        val flutter = childFragmentManager.findFragmentByTag("bookmark-ui") as? BookmarkEditorFlutterFragment
        if (flutter?.flutterEngine?.dartExecutor?.isExecutingDart == true) flutter.onBackPressed()
        else coordinator.requestClose()
    }
    override fun dismiss() { requestBackFromHost() }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }

    override fun onStop() {
        lifecycleScope.launch { runCatching { model.flushDraft() } }
        super.onStop()
    }


    override fun onCancel(dialog: DialogInterface) {
        model.cancel()
        super.onCancel(dialog)
    }
}
