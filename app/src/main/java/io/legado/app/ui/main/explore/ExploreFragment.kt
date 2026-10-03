package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.startActivity

/** Main's public fragment/position and repeated-tab collapse entry points remain stable. */
class ExploreFragment() : Fragment(), MainFragmentInterface {
    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int?
        get() = arguments?.getInt("position")

    internal val homeModel by
        viewModels<ExploreHomeViewModel> {
            viewModelFactory {
                initializer {
                    val savedState = createSavedStateHandle()
                    ExploreHomeViewModel(
                        AppExploreHomeRepository(),
                        FileExploreHomeSessionStorage(
                            requireContext().applicationContext,
                            ExploreHomeViewModel.token(savedState),
                        ),
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
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme { ExploreHomeRoute(homeModel, ::ready, ::native) } }
        }

    private fun ready(): Boolean =
        isAdded &&
            isResumed &&
            activity?.isFinishing == false &&
            !parentFragmentManager.isStateSaved

    private fun native(prepared: ExploreHomePrepared) {
        val effect = prepared.effect
        when (effect.action) {
            "manage" -> startActivity<BookSourceActivity>()
            "edit" ->
                startActivity<BookSourceEditActivity> { putExtra("sourceUrl", effect.sourceUrl) }
            "login" ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", effect.sourceUrl)
                }
            "search" -> prepared.searchSource?.let { SearchActivity.start(requireContext(), it) }
            "open" ->
                startActivity<ExploreShowActivity> {
                    putExtra("exploreName", effect.title)
                    putExtra("sourceUrl", effect.sourceUrl)
                    putExtra("exploreUrl", effect.value)
                }
            "script" -> homeModel.execute(effect, activity as? AppCompatActivity)
        }
    }

    fun compressExplore() {
        homeModel.compressExplore()
    }
}
