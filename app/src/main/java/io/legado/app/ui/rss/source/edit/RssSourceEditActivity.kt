package io.legado.app.ui.rss.source.edit

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.data.repository.RoomRssSourceEditorRepository
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.utils.*
import java.io.File

class RssSourceEditActivity : BaseComposeActivity(), VariableDialog.Callback {
    val viewModel by
        viewModels<RssSourceEditorViewModel> {
            viewModelFactory {
                initializer {
                    RssSourceEditorViewModel(
                        RoomRssSourceEditorRepository(applicationContext),
                        createSavedStateHandle(),
                        intent.getStringExtra("sourceUrl"),
                    )
                }
            }
        }
    private val editor =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.editorReturned(
                result.resultCode == RESULT_OK,
                result.data?.getStringExtra("text"),
                result.data?.getStringExtra("textFile"),
                result.data?.getIntExtra("cursorPosition", -1) ?: -1,
            )
        }
    private val file =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let {
                viewModel.insert(if (it.isContentScheme()) it.toString() else it.path.toString())
            }
        }
    private val qr =
        registerForActivityResult(QrCodeResult()) { result -> result?.let(viewModel::paste) }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) showHelp("rssRuleHelp")
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        RssSourceEditorRoute(
            viewModel,
            ::deliver,
            ::close,
            { toastOnUi(it) },
            { !supportFragmentManager.isStateSaved },
            { showDialogFragment<KeyboardAssistsConfig>() },
        )
    }

    private fun close(saved: Boolean) {
        if (saved) setResult(RESULT_OK)
        super.finish()
    }

    private fun deliver(effect: RssSourceEditorEffect, payload: String?) {
        when (effect.kind) {
            RssSourceEditorEffectKind.SavedDebug -> {
                setResult(RESULT_OK)
                startActivity<RssSourceDebugActivity> { putExtra("key", viewModel.sourceUrl) }
            }
            RssSourceEditorEffectKind.SavedLogin -> {
                setResult(RESULT_OK)
                startActivity<SourceLoginActivity> {
                    putExtra("type", "rssSource")
                    putExtra("key", viewModel.sourceUrl)
                }
            }
            RssSourceEditorEffectKind.SavedVariable -> {
                setResult(RESULT_OK)
                val comment =
                    listOf(viewModel.variableComment, "源变量可在js中通过source.getVariable()获取")
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        requireNotNull(viewModel.sourceUrl),
                        payload,
                        comment,
                    )
                )
            }
            RssSourceEditorEffectKind.SavedOnly -> setResult(RESULT_OK)
            RssSourceEditorEffectKind.Clipboard -> sendToClip(requireNotNull(payload))
            RssSourceEditorEffectKind.Paste -> viewModel.paste(getClipText())
            RssSourceEditorEffectKind.ScanQr -> qr.launch(null)
            RssSourceEditorEffectKind.ShareText -> share(requireNotNull(payload))
            RssSourceEditorEffectKind.ShareQr -> {
                val uri =
                    FileProvider.getUriForFile(
                        this,
                        AppConst.authority,
                        File(requireNotNull(payload)),
                    )
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        getString(R.string.share_rss_source),
                    )
                )
            }
            RssSourceEditorEffectKind.Log -> showDialogFragment<AppLogDialog>()
            RssSourceEditorEffectKind.Help -> showHelp("rssRuleHelp")
            RssSourceEditorEffectKind.JsHelp -> showHelp("jsHelp")
            RssSourceEditorEffectKind.RegexHelp -> showHelp("regexHelp")
            RssSourceEditorEffectKind.File -> file.launch { mode = HandleFileContract.FILE }
            RssSourceEditorEffectKind.UrlOptions -> UrlOptionDialog(this, viewModel::insert).show()
            RssSourceEditorEffectKind.Editor ->
                editor.launch(
                    Intent(this, CodeEditActivity::class.java).apply {
                        putExtra("textFile", effect.path)
                        putExtra("useTextFile", true)
                        putExtra("returnUnchangedText", true)
                        putExtra(
                            "title",
                            effect.field?.labelResource()?.let(::getString)
                                ?: effect.field?.literalLabel(),
                        )
                        putExtra("cursorPosition", effect.cursor)
                    }
                )
            RssSourceEditorEffectKind.Close,
            RssSourceEditorEffectKind.SavedClose -> Unit
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.setVariable(key, variable)
    }

    override fun finish() {
        viewModel.requestExit()
    }
}
