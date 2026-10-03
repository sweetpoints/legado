package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.repository.AppBottomBarSkinCatalogRepository
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class BottomBarSkinActivity : BaseComposeActivity() {
    internal val model by
        viewModels<BottomBarSkinCatalogViewModel> {
            viewModelFactory {
                initializer {
                    BottomBarSkinCatalogViewModel(
                        AppBottomBarSkinCatalogRepository(this@BottomBarSkinActivity),
                        createSavedStateHandle(),
                        24.dpToPx(),
                    )
                }
            }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            model.importResult(result.uri?.toString())
        }
    private val exportDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            model.exportResult(result.uri != null)
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BottomBarSkinCatalogRoute(model, { !isFinishing }, ::close, ::deliver, { toastOnUi(it) })
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { close() }
    }

    private fun close() {
        if (!model.state.value.closeBlocked) finish()
    }

    private fun deliver(effect: BottomBarSkinCatalogEffect) {
        when (effect.type) {
            BottomBarSkinCatalogEffectType.Import ->
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.bottom_bar_skin_import)
                    allowExtensions =
                        arrayOf("zip", "ziP", "zIp", "zIP", "Zip", "ZiP", "ZIp", "ZIP")
                }
            BottomBarSkinCatalogEffectType.Assign ->
                startActivity(
                    Intent(this, BottomBarSkinAssignActivity::class.java)
                        .putExtra("name", effect.name)
                        .putExtra("sessionId", checkNotNull(effect.session))
                        .putExtra("editName", effect.editName)
                )
            BottomBarSkinCatalogEffectType.Export ->
                exportDoc.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData(
                            "${effect.name}.zip",
                            File(checkNotNull(effect.path)),
                            "application/zip",
                        )
                }
            BottomBarSkinCatalogEffectType.Share ->
                share(File(checkNotNull(effect.path)), "application/zip")
            BottomBarSkinCatalogEffectType.Changed -> postEvent(EventBus.BOTTOM_BAR_SKIN, "")
            BottomBarSkinCatalogEffectType.Exported -> toastOnUi(R.string.export_success)
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations)
            lifecycleScope.launch(NonCancellable) { runCatching { model.releasePending() } }
        super.onDestroy()
    }
}
