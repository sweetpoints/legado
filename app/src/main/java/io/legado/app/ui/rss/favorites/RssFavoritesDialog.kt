package io.legado.app.ui.rss.favorites

import io.legado.app.utils.resizeForIme

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.FileRssFavoriteConfigRepository
import io.legado.app.utils.setLayout
import kotlinx.coroutines.launch
import splitties.init.appCtx

class RssFavoritesDialog() : BaseDialogFragment(0) {
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
        val legacy = arguments
        if (legacy?.containsKey("requestId") != true)
            arguments =
                request(legacy?.getString("title"), legacy?.getString("group")).apply {
                    putBoolean("emptyRequest", legacy == null)
                }
        coordinator
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
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
        dialog?.window?.resizeForIme()
    }

    internal val coordinator by lazy {
        RssFavoriteConfigNativeCoordinator(model, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved },
            { title, group -> callback?.updateFavorite(title, group) },
            { callback?.deleteFavorite() }, ::dismissAllowingStateLoss, { isCancelable = it })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.rss_favorite_config_flutter_content }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        if (arguments?.getBoolean("emptyRequest") == true) model.cancel()
        if (childFragmentManager.findFragmentByTag("rss-favorite-config-flutter") == null)
            childFragmentManager.beginTransaction().add(R.id.rss_favorite_config_flutter_content,
                RssFavoriteConfigFlutterFragment(), "rss-favorite-config-flutter").commitNow()
    }

    override fun onResume() {
        super.onResume()
        view?.post {
            if (isResumed) (childFragmentManager.findFragmentByTag("rss-favorite-config-flutter")
                as? RssFavoriteConfigFlutterFragment)?.onPostResume()
        }
    }

    override fun onStop() {
        lifecycleScope.launch { runCatching { model.flushDraft() } }
        super.onStop()
    }

    override fun onDestroy() {
        coordinator.close()
        super.onDestroy()
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
