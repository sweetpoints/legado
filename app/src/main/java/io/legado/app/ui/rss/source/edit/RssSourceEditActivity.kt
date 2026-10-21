package io.legado.app.ui.rss.source.edit

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity
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

class RssSourceEditActivity : BaseThemedActivity(), VariableDialog.Callback {
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
    internal val editorCoordinator by lazy {
        RssSourceEditorNativeCoordinator(this, viewModel, lifecycle, lifecycleScope,
            ::canHandleEditorEffect, ::deliver, ::registerNative, ::close,
            { toastOnUi(it) }, { viewModel.paste(getClipText()) },
            { showDialogFragment<KeyboardAssistsConfig>() })
    }
    private data class RegisteredNative(val id: String, val launch: () -> Unit, val unregister: () -> Unit)
    private var registered: RegisteredNative? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = editorCoordinator.requestBack()
        })
        super.onCreate(savedInstanceState)
    }

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.rss_source_editor_flutter_container })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        editorCoordinator
        if (editorFragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.rss_source_editor_flutter_container, RssSourceEditorFlutterFragment(), FRAGMENT_TAG)
            .commitNow()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) showHelp("rssRuleHelp")
    }

    private fun canHandleEditorEffect() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved
    private val editorFragment get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? RssSourceEditorFlutterFragment
    override fun onPostResume() { super.onPostResume(); editorFragment?.onPostResume(); editorCoordinator.onHostReady() }
    override fun onNewIntent(intent: Intent) { editorFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        editorFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        editorFragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); editorFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); editorFragment?.onTrimMemory(level) }
    override fun onDestroy() {
        registered?.unregister?.invoke(); registered = null
        editorCoordinator.close()
        super.onDestroy()
    }

    private fun registerNative() {
        val editor = viewModel.pendingEditorRequest
        val insertion = viewModel.nativeOwner
        val id = editor?.id ?: insertion?.id
        if (registered?.id == id) return
        registered?.unregister?.invoke()
        registered = null
        if (editor != null) {
            val launcher = activityResultRegistry.register("rss-editor-${editor.id}",
                ActivityResultContracts.StartActivityForResult()) { result ->
                viewModel.editorReturned(editor.id, result.resultCode == RESULT_OK,
                    result.data?.getStringExtra("text"), result.data?.getStringExtra("textFile"),
                    result.data?.getIntExtra("cursorPosition", -1) ?: -1)
            }
            registered = RegisteredNative(editor.id, { launcher.launch(editorIntent(editor)) }, launcher::unregister)
        } else if (insertion != null) {
            when (insertion.kind) {
                RssSourceEditorEffectKind.ScanQr -> {
                    val launcher = activityResultRegistry.register("rss-native-${insertion.id}", QrCodeResult()) { result ->
                        viewModel.nativeReturned(insertion.id, result)
                    }
                    registered = RegisteredNative(insertion.id, { launcher.launch(null) }, launcher::unregister)
                }
                RssSourceEditorEffectKind.File -> {
                    val launcher = activityResultRegistry.register("rss-native-${insertion.id}", HandleFileContract()) { result ->
                        val uri = result.uri
                        viewModel.nativeReturned(insertion.id, uri?.let { if (it.isContentScheme()) it.toString() else it.path })
                    }
                    registered = RegisteredNative(insertion.id, { launcher.launch { mode = HandleFileContract.FILE } }, launcher::unregister)
                }
                RssSourceEditorEffectKind.UrlOptions -> {
                    registered = RegisteredNative(insertion.id, {
                        UrlOptionDialog(this) { value ->
                            if (canHandleEditorEffect()) viewModel.nativeReturned(insertion.id, value)
                        }.apply {
                            setOnDismissListener {
                                if (canHandleEditorEffect()) viewModel.nativeReturned(insertion.id, null)
                            }
                        }.show()
                    }, {})
                    // ActivityResultRegistry restores Activity results; this local dialog must
                    // be restored explicitly after its durable launch effect was consumed.
                    if (viewModel.state.value.effects.none { it.id == insertion.id }) registered!!.launch()
                }
                else -> Unit
            }
        }
    }

    private fun launchRegistered(effect: RssSourceEditorEffect) {
        check(registered?.id == effect.id) { "Missing native result owner" }
        registered!!.launch()
    }

    private fun editorIntent(effect: RssSourceEditorEffect) = Intent(this, CodeEditActivity::class.java).apply {
        putExtra("textFile", effect.path)
        putExtra("useTextFile", true)
        putExtra("returnUnchangedText", true)
        putExtra("title", effect.field?.labelResource()?.let(::getString) ?: effect.field?.literalLabel())
        putExtra("cursorPosition", effect.cursor)
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
            RssSourceEditorEffectKind.ScanQr -> launchRegistered(effect)
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
            RssSourceEditorEffectKind.File -> launchRegistered(effect)
            RssSourceEditorEffectKind.UrlOptions -> launchRegistered(effect)
            RssSourceEditorEffectKind.Editor -> launchRegistered(effect)
            RssSourceEditorEffectKind.Close,
            RssSourceEditorEffectKind.SavedClose -> Unit
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.setVariable(key, variable)
    }

    override fun finish() {
        editorCoordinator.requestBack()
    }
    private companion object { const val FRAGMENT_TAG = "rss-source-editor-flutter" }
}
