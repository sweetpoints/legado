package io.legado.app.ui.login

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.*
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * Public Intent contract stays intact; large entry strings are owned by the private UUID session.
 */
class SourceLoginActivity : BaseComposeActivity(transparent = true, imageBg = false) {
    internal val sourceModel by viewModels<SourceLoginViewModel>()
    val viewModel: SourceLoginViewModel
        get() = sourceModel

    internal val hostModel by
        viewModels<SourceLoginHostViewModel> {
            viewModelFactory {
                initializer {
                    SourceLoginHostViewModel(
                        AppSourceLoginRepository(),
                        FileSourceLoginSessionRepository(),
                        createSavedStateHandle().apply {
                            keys()
                                .filterNot { it.startsWith("sourceLogin.") }
                                .forEach { remove<Any?>(it) }
                        },
                    )
                }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        // The prior Fragment content shell is replaced by the direct Compose destination.
        supportFragmentManager.fragments.filterIsInstance<WebViewLoginFragment>().forEach {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        hostModel.bind(
            if (savedInstanceState == null)
                SourceLoginRequest(
                    intent.getIntExtra("bookType", 0),
                    intent.getStringExtra("type"),
                    intent.getStringExtra("key"),
                    intent.getStringExtra("bookUrl"),
                )
            else null
        )
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        SourceLoginHostRoute(
            hostModel,
            ::ready,
            sourceModel::applyInitialized,
            {
                if (supportFragmentManager.findFragmentByTag("SourceLoginDialog") == null)
                    showDialogFragment<SourceLoginDialog>()
            },
            ::finish,
            { message ->
                if (message != null) AppLog.put("登录 UI 初始化失败\n$message")
                toastOnUi(message ?: "未找到书源")
            },
            { openUrl(it) },
        )
    }

    private fun ready() =
        !isFinishing &&
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
            !supportFragmentManager.isStateSaved
}
