package io.legado.app.ui.rss.subscription

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.FileRuleSubscriptionDraftRepository
import io.legado.app.data.repository.RoomRuleSubscriptionRepository
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class RuleSubActivity : BaseComposeActivity() {
    val viewModel by
        viewModels<RuleSubscriptionViewModel> {
            viewModelFactory {
                initializer {
                    val repository = RoomRuleSubscriptionRepository()
                    RuleSubscriptionViewModel(
                        repository,
                        FileRuleSubscriptionDraftRepository(applicationContext, repository),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        RuleSubscriptionRoute(
            viewModel,
            { super.finish() },
            { request ->
                when (request.type) {
                    0 -> showDialogFragment(ImportBookSourceDialog(request.url))
                    1 -> showDialogFragment(ImportRssSourceDialog(request.url))
                    2 -> showDialogFragment(ImportReplaceRuleDialog(request.url))
                }
            },
            { toastOnUi(it) },
            { !supportFragmentManager.isStateSaved },
        )
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }
}
