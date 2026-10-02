package io.legado.app.ui.main.my

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.showHelp

class MyMoreActivity : BaseComposeActivity() {
    private val viewModel by viewModels<MyViewModel>()

    override fun onComposeCreated(savedInstanceState: Bundle?) { setTitle(R.string.reader_menu_more) }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        Box(Modifier.navigationBarsPadding()) {
            MyRoute(
                viewModel, isMore = true, onItemClick = ::openMyItem,
                onLongClick = ::showMyServiceActions, onHelp = { showHelp("appHelp") }, onBack = ::finish,
            )
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) { recreate() }
        observeEventSticky<String>(EventBus.WEB_SERVICE, EventBus.MCP_SERVICE) { viewModel.refreshRuntimeState() }
    }
}
