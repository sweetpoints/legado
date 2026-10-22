package io.legado.app.ui.rss.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RoomRssFavoriteListRepository
import io.legado.app.ui.rss.read.ReadRss

class RssFavoritesFragment() : Fragment() {
    constructor(group: String) : this() {
        arguments = Bundle().apply { putString("group", group) }
    }

    internal val model by
        viewModels<RssFavoriteListViewModel> {
            viewModelFactory {
                initializer {
                    RssFavoriteListViewModel(
                        RoomRssFavoriteListRepository(),
                        createSavedStateHandle(),
                        arguments?.getString("group"),
                    )
                }
            }
        }

    internal lateinit var coordinator: RssFavoritesNativeCoordinator
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator =
            RssFavoritesNativeCoordinator(
                model,
                lifecycle,
                lifecycleScope,
                arguments?.containsKey("group") != true,
                { isAdded && !parentFragmentManager.isStateSaved },
                ::readRss,
                ::dispatchNativeBack,
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = R.id.rss_favorites_compose_content }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("rss-favorites-flutter") == null)
            childFragmentManager
                .beginTransaction()
                .add(
                    R.id.rss_favorites_compose_content,
                    RssFavoritesFlutterFragment(),
                    "rss-favorites-flutter",
                )
                .commitNow()
    }

    internal val flutterView
        get() =
            childFragmentManager.findFragmentByTag("rss-favorites-flutter")
                as? RssFavoritesFlutterFragment

    internal fun dispatchNativeBack() {
        val flutter = flutterView
        val handled = flutter?.backCallbackState ?: false
        flutter?.setFrameworkHandlesBack(false)
        try {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        } finally {
            if (isAdded) flutter?.setFrameworkHandlesBack(handled)
        }
    }

    override fun onResume() {
        super.onResume()
        view?.post {
            if (isResumed) {
                flutterView?.onPostResume()
                coordinator.onHostReady(lifecycleScope)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        flutterView?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onDestroy() {
        coordinator.close()
        super.onDestroy()
    }

    fun readRss(rssStar: RssStar) {
        ReadRss.readRss(this, rssStar.toRssArticle())
    }

    fun delStar(rssStar: RssStar) {
        model.requestDelete(RoomRssFavoriteListRepository.key(rssStar.origin, rssStar.link))
    }
}
