package io.legado.app.ui.book.explore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppExploreResultsRepository
import io.legado.app.data.repository.AppExploreResultsSessionRepository
import io.legado.app.data.repository.ExploreResultsRequest
import io.legado.app.ui.book.info.BookInfoNavigation
import io.legado.app.utils.toastOnUi
import java.util.UUID

/** Source-defined results in Compose; the established legacy Intent extras remain supported. */
class ExploreShowActivity : BaseComposeActivity() {
    internal val resultsModel by
        viewModels<ExploreResultsViewModel> {
            viewModelFactory {
                initializer {
                    val saved =
                        createSavedStateHandle().apply {
                            // Intent defaults can contain unrestricted JS/URLs. Retain only owned
                            // small
                            // saved values; the repository transfers the full input to a private
                            // session.
                            keys()
                                .filterNot { it.startsWith("exploreResults.") }
                                .forEach { remove<Any?>(it) }
                            if (get<String>("exploreResults.session") == null) {
                                intent.getStringExtra(PREPARED_SESSION)?.let { session ->
                                    UUID.fromString(session)
                                    this["exploreResults.session"] = session
                                }
                            }
                        }
                    ExploreResultsViewModel(
                        saved,
                        AppExploreResultsRepository(),
                        AppExploreResultsSessionRepository(),
                        AppExploreResultsBookNavigation(applicationContext),
                    )
                }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val legacyInput =
            if (intent.hasExtra(PREPARED_SESSION)) null
            else
                ExploreResultsRequest(
                    intent.getStringExtra("sourceUrl").orEmpty(),
                    intent.getStringExtra("exploreName").orEmpty(),
                    intent.getStringExtra("exploreUrl").orEmpty(),
                )
        resultsModel.load(legacyInput)
        onBackPressedDispatcher.addCallback(this) { resultsModel.close() }
    }

    private fun ready(): Boolean =
        lifecycle.currentState == Lifecycle.State.RESUMED &&
            !isFinishing &&
            !isDestroyed &&
            !supportFragmentManager.isStateSaved

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        ExploreResultsRoute(
            resultsModel,
            ::ready,
            ::finish,
            native = { ticket -> startActivity(BookInfoNavigation.intent(this, ticket)) },
            notice = { toastOnUi(it) },
        )
    }

    companion object {
        const val PREPARED_SESSION = "exploreResultsPreparedSession"

        /** Only a validated UUID crosses the Activity boundary. Preparation stays in IO. */
        fun startPrepared(context: Context, sessionId: String) {
            UUID.fromString(sessionId)
            val intent =
                Intent(context, ExploreShowActivity::class.java)
                    .putExtra(PREPARED_SESSION, sessionId)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
