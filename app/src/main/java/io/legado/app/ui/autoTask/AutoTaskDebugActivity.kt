package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppAutoTaskDebugRepository

class AutoTaskDebugActivity : BaseComposeActivity() {
    val viewModel by
        viewModels<AutoTaskDebugViewModel> {
            viewModelFactory {
                initializer {
                    AutoTaskDebugViewModel(
                        AppAutoTaskDebugRepository(applicationContext),
                        createSavedStateHandle(),
                        intent.getStringExtra(EXTRA_ID),
                    )
                }
            }
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        AutoTaskDebugRoute(viewModel, onBack = ::finish, onTaskMissing = ::finish)
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }

    companion object {
        internal const val EXTRA_ID = "autoTaskId"

        fun intent(context: Context, id: String): Intent =
            Intent(context, AutoTaskDebugActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
