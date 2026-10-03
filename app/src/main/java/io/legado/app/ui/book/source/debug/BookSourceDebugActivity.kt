package io.legado.app.ui.book.source.debug

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppBookSourceDebugRepository
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi

class BookSourceDebugActivity : BaseComposeActivity() {
    val viewModel by viewModels<BookSourceDebugViewModel> { viewModelFactory { initializer {
        BookSourceDebugViewModel(AppBookSourceDebugRepository(applicationContext),createSavedStateHandle().apply { remove<String>("key") },intent.getStringExtra("key"))
    } } }
    private val qrCodeResult=registerForActivityResult(QrCodeResult()) { result -> result?.let(viewModel::run) }
    @Composable override fun Content(savedInstanceState:Bundle?) {
        val transparent=transparentNavBar && !AppConfig.isEInkMode
        BookSourceDebugRoute(viewModel,{super.finish()},{showDialogFragment(TextDialog("html",it))},{toastOnUi(it)},
            {qrCodeResult.launch()},{showHelp("debugHelp")},BookSourceDebugStyle(transparent,Color(getToolbarTextColor(transparent))),{!supportFragmentManager.isStateSaved})
    }
    override fun finish() { viewModel.close();super.finish() }
}
