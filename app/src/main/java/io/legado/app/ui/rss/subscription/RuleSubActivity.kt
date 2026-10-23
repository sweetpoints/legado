package io.legado.app.ui.rss.subscription

import android.os.Bundle
import androidx.activity.viewModels
import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import io.legado.app.R
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.FileRuleSubscriptionDraftRepository
import io.legado.app.data.repository.RoomRuleSubscriptionRepository
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class RuleSubActivity : BaseThemedActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.editor != null) viewModel.cancelEditor()
                else if (!viewModel.state.value.busy) viewModel.close()
            }
        })
        super.onCreate(savedInstanceState)
    }

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.rule_subscription_flutter_container })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (fragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.rule_subscription_flutter_container, RuleSubscriptionFlutterFragment(), TAG)
            .commitNow()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.state.collect { state ->
                    if (supportFragmentManager.isStateSaved) return@collect
                    if (state.closed) { finish(); return@collect }
                    if (state.busy || !state.loaded || state.issue != null) return@collect
                    val navigation = state.navigation ?: return@collect
                    try {
                        viewModel.consumeOpen(navigation.token) {
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                !supportFragmentManager.isStateSaved && !isFinishing
                        }?.let { request ->
                            when (request.type) {
                                0 -> showDialogFragment(ImportBookSourceDialog(request.url))
                                1 -> showDialogFragment(ImportRssSourceDialog(request.url))
                                2 -> showDialogFragment(ImportReplaceRuleDialog(request.url))
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        viewModel.openFailure(failure)
                        toastOnUi(failure.localizedMessage ?: "Error")
                    }
                }
            }
        }
    }

    private val fragment get() = supportFragmentManager.findFragmentByTag(TAG) as? RuleSubscriptionFlutterFragment
    override fun onPostResume() { super.onPostResume(); fragment?.onPostResume() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); fragment?.onNewIntent(intent) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        fragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); fragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); fragment?.onTrimMemory(level) }
    override fun onStop() {
        viewModel.cancelDrag()
        if (!viewModel.state.value.closed) lifecycleScope.launch(NonCancellable) { runCatching { viewModel.flush() } }
        super.onStop()
    }
    private companion object { const val TAG = "rule-subscription-flutter" }

    override fun finish() {
        viewModel.close()
        super.finish()
    }
}
