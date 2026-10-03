package io.legado.app.ui.book.read.config

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.AppSpeakEngineRepository
import io.legado.app.model.ReadAloud
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

internal fun HttpTTS.hasLoginCapability() = !loginUrl.isNullOrBlank() || !loginUi.isNullOrBlank()

internal fun HttpTTS.shouldOpenLoginOnSelection() = hasLoginCapability()

class SpeakEngineDialog : BaseComposeDialogFragment() {
    private val viewModel by
        viewModels<SpeakEngineViewModel> {
            viewModelFactory {
                initializer {
                    SpeakEngineViewModel(
                        AppSpeakEngineRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val importResult =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.importResult(it.toString()) }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.exported(it.toString()) }
        }

    override fun onStart() {
        super.onStart()
        dialog
            ?.window
            ?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        SpeakEngineRoute(
            viewModel,
            { isAdded && !childFragmentManager.isStateSaved },
            ::handle,
            { dismissAllowingStateLoss() },
            { requireContext().sendToClip(it) },
            Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * 0.9f),
        )
    }

    private fun handle(effect: SpeakEngineEffect) {
        when (effect.action) {
            SpeakEngineAction.Login ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "httpTts")
                    putExtra("key", effect.argument)
                }
            SpeakEngineAction.Edit ->
                showDialogFragment(
                    effect.argument.toLongOrNull()?.let { HttpTtsEditDialog(it) }
                        ?: HttpTtsEditDialog()
                )
            SpeakEngineAction.ImportLocal ->
                importResult.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            SpeakEngineAction.Import -> showDialogFragment(ImportHttpTtsDialog(effect.argument))
            SpeakEngineAction.Export ->
                effect.export?.let { data ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData =
                            HandleFileContract.FileData(data.name, data.bytes, "application/json")
                    }
                }
            SpeakEngineAction.Applied -> {
                (parentFragment as? CallBack)?.upSpeakEngineSummary()
                ReadAloud.upReadAloudClass()
                dismissAllowingStateLoss()
            }
            SpeakEngineAction.ClearCache -> {
                ReadAloud.upReadAloudClass()
                viewModel.clearCacheData()
            }
            SpeakEngineAction.CacheCleared -> toastOnUi(R.string.clear_cache_success)
            SpeakEngineAction.SystemExport -> toastOnUi(R.string.is_system_tts_no_export)
        }
    }

    fun clearCache() = viewModel.clearCache()

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}
