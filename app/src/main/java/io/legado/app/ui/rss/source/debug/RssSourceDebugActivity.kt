package io.legado.app.ui.rss.source.debug

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppRssSourceDebugRepository
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class RssSourceDebugActivity : BaseComposeActivity() {
    val viewModel by
        viewModels<RssSourceDebugViewModel> {
            viewModelFactory {
                initializer {
                    RssSourceDebugViewModel(
                        AppRssSourceDebugRepository(applicationContext),
                        createSavedStateHandle(),
                        intent.getStringExtra("key"),
                    )
                }
            }
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        RssSourceDebugRoute(
            viewModel,
            { super.finish() },
            { showDialogFragment(TextDialog("Html", it)) },
            { toastOnUi(it) },
            { !supportFragmentManager.isStateSaved },
        )
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }
}
