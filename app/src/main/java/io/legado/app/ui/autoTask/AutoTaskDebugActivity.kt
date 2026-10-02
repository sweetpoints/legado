package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class AutoTaskDebugActivity : BaseComposeActivity() {
    private val viewModel by viewModels<AutoTaskDebugViewModel>()

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        AutoTaskDebugRoute(viewModel, onBack = ::finish, onTaskMissing = ::finish)
    }

    companion object {
        internal const val EXTRA_ID = "autoTaskId"

        fun intent(context: Context, id: String): Intent =
            Intent(context, AutoTaskDebugActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
