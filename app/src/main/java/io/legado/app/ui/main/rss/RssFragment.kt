package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.data.repository.*
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

/** Keep Main's Fragment/position entry point while the entire destination is Compose. */
class RssFragment() : Fragment(), MainFragmentInterface {
    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int?
        get() = arguments?.getInt("position")

    internal val homeModel by
        viewModels<MainRssViewModel> {
            viewModelFactory {
                initializer {
                    MainRssViewModel(
                        AppMainRssRepository(),
                        FileMainRssSessionRepository(),
                        createSavedStateHandle().apply {
                            keys()
                                .filterNot { it.startsWith("mainRss.") }
                                .forEach { remove<Any?>(it) }
                        },
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
            setContent {
                LegadoComposeTheme {
                    val context = LocalContext.current
                    val images = remember(context) { GlideRssArticleImageRepository(context) }
                    MainRssRoute(homeModel, images, ::ready, ::native)
                }
            }
        }

    private fun ready() =
        isAdded &&
            isResumed &&
            activity?.isFinishing == false &&
            !parentFragmentManager.isStateSaved

    private fun native(request: MainRssPrepared, readerTicket: String?) {
        when (MainRssAction.valueOf(request.action)) {
            MainRssAction.Open ->
                request.navigation?.let { navigation ->
                    when (navigation.destination) {
                        MainRssDestination.Categories ->
                            startActivity<RssSortActivity> {
                                putExtra("sourceUrl", navigation.sourceUrl)
                            }
                        MainRssDestination.ReaderLink ->
                            ReadRssActivity.startPrepared(
                                requireContext(),
                                requireNotNull(readerTicket),
                            )
                        MainRssDestination.ReaderHtml ->
                            ReadRssActivity.startPrepared(
                                requireContext(),
                                requireNotNull(readerTicket),
                            )
                        MainRssDestination.External ->
                            navigation.value?.let { requireContext().openUrl(it) }
                    }
                }
            MainRssAction.Edit ->
                request.sourceUrl?.let { url ->
                    startActivity<RssSourceEditActivity> { putExtra("sourceUrl", url) }
                }
            MainRssAction.Login ->
                request.sourceUrl?.let { url ->
                    startActivity<SourceLoginActivity> {
                        putExtra("type", "rssSource")
                        putExtra("key", url)
                    }
                }
            MainRssAction.Subscriptions -> startActivity<RuleSubActivity>()
            MainRssAction.History -> showDialogFragment<ReadRecordDialog>()
            MainRssAction.Favorites -> startActivity<RssFavoritesActivity>()
            MainRssAction.Settings -> startActivity<RssSourceActivity>()
        }
    }
}
