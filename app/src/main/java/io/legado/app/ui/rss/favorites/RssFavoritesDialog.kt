package io.legado.app.ui.rss.favorites

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
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.FileRssFavoriteConfigRepository
import io.legado.app.utils.setLayout
import kotlinx.coroutines.launch
import splitties.init.appCtx

class RssFavoritesDialog() : BaseComposeDialogFragment() {
    constructor(rssArticle: RssArticle) : this() {
        arguments = request(rssArticle.title, rssArticle.group)
    }

    constructor(rssStar: RssStar) : this() {
        arguments = request(rssStar.title, rssStar.group)
    }

    private fun request(title: String?, group: String?) =
        Bundle().apply {
            putString("requestId", FileRssFavoriteConfigRepository.stage(appCtx, title, group))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = arguments
        if (legacy?.containsKey("requestId") != true)
            arguments =
                request(legacy?.getString("title"), legacy?.getString("group")).apply {
                    putBoolean("emptyRequest", legacy == null)
                }
    }

    internal val model by
        viewModels<RssFavoriteConfigViewModel> {
            viewModelFactory {
                initializer {
                    RssFavoriteConfigViewModel(
                        FileRssFavoriteConfigRepository(requireContext()),
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
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        requireView().setBackgroundColor(Color.TRANSPARENT)
        if (arguments?.getBoolean("emptyRequest") == true) model.cancel()
    }

    override fun onStop() {
        lifecycleScope.launch { runCatching { model.flushDraft() } }
        super.onStop()
    }

    @Composable
    override fun Content() {
        RssFavoriteConfigRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            { title, group -> callback?.updateFavorite(title, group) },
            { callback?.deleteFavorite() },
            ::dismissAllowingStateLoss,
            { isCancelable = it },
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        model.cancel()
        super.onCancel(dialog)
    }

    val callback
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    interface Callback {
        fun updateFavorite(title: String?, group: String?)

        fun deleteFavorite()
    }
}
