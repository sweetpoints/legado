package io.legado.app.ui.main.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import io.legado.app.constant.EventBus
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.showHelp

/** Temporary Fragment entry point; the destination's content is entirely Compose. */
class MyFragment() : Fragment(), MainFragmentInterface {
    internal var restoreWithoutPageView: Boolean = false

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int?
        get() = arguments?.getInt("position")

    private val viewModel by viewModels<MyViewModel>()

    internal fun captureCustomizationDraftForHostMigration(): MyCustomizationDraft? =
        viewModel.captureCustomizationDraftForHostMigration()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        if (restoreWithoutPageView) return null
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    MyRoute(
                        viewModel,
                        isMore = false,
                        onItemClick = { (requireActivity() as AppCompatActivity).openMyItem(it) },
                        onLongClick = {
                            (requireActivity() as AppCompatActivity).showMyServiceActions(it)
                        },
                        onHelp = { showHelp("appHelp") },
                        onBack = {},
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEventSticky<String>(EventBus.WEB_SERVICE, EventBus.MCP_SERVICE) {
            viewModel.refreshRuntimeState()
        }
    }
}
