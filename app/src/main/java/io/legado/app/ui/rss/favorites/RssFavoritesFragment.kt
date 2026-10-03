package io.legado.app.ui.rss.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RoomRssFavoriteListRepository
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoComposeTheme

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            id = R.id.rss_favorites_compose_content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    RssFavoriteListRoute(
                        model,
                        arguments?.containsKey("group") != true,
                        { isAdded && !parentFragmentManager.isStateSaved },
                        ::readRss,
                        { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    )
                }
            }
        }

    fun readRss(rssStar: RssStar) {
        ReadRss.readRss(this, rssStar.toRssArticle())
    }

    fun delStar(rssStar: RssStar) {
        model.requestDelete(RoomRssFavoriteListRepository.key(rssStar.origin, rssStar.link))
    }
}
