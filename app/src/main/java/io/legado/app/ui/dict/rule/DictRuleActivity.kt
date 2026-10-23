package io.legado.app.ui.dict.rule

import android.os.Bundle
import androidx.activity.viewModels
import android.content.Intent
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import io.legado.app.R
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.RoomDictionaryRuleManagementRepository
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import java.io.File

class DictRuleActivity : BaseThemedActivity() {
    val viewModel by
        viewModels<DictionaryRuleManagementViewModel> {
            viewModelFactory {
                initializer {
                    DictionaryRuleManagementViewModel(
                        RoomDictionaryRuleManagementRepository(applicationContext),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val qrCodeResult =
        registerForActivityResult(QrCodeResult()) { value ->
            value?.let { showDialogFragment(ImportDictRuleDialog(it)) }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { showDialogFragment(ImportDictRuleDialog(it.toString())) }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.exportFinished(it.toString()) }
        }

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.dictionary_manager_flutter_container })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (managerFragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.dictionary_manager_flutter_container, DictionaryManagerFlutterFragment(), "dictionary-manager")
            .commitNow()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.state.collect {
                    deliverPendingEffect()
                }
            }
        }
    }

    private fun deliverPendingEffect() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved)
            viewModel.consumeEffect()?.let(::deliver)
    }

    internal fun nativeAction(op: String, name: String? = null) {
        check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        check(!isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved)
        when (op) {
            "back" -> {
                viewModel.cancelGestures()
                val state = viewModel.state.value
                when {
                    state.online -> viewModel.showOnline(false)
                    state.deleteName != null -> viewModel.requestDelete(null)
                    state.exportUrl != null -> viewModel.closeExport()
                    state.passphrase != null -> viewModel.closePassphrase()
                    else -> finish()
                }
            }
            "add" -> showDialogFragment(DictRuleEditDialog())
            "edit" -> showDialogFragment(DictRuleEditDialog(checkNotNull(name)))
            "importFile" -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            "importQr" -> qrCodeResult.launch(null)
            "help" -> showHelp("dictRuleHelp")
            else -> error("Unknown native action")
        }
    }

    private val managerFragment: DictionaryManagerFlutterFragment?
        get() = supportFragmentManager.findFragmentByTag("dictionary-manager") as? DictionaryManagerFlutterFragment
    override fun onPostResume() { super.onPostResume(); managerFragment?.onPostResume(); deliverPendingEffect() }
    override fun onNewIntent(intent: Intent) { managerFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        managerFragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        managerFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); managerFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); managerFragment?.onTrimMemory(level) }

    private fun deliver(effect: DictionaryManagementEffect) {
        when (effect.kind) {
            DictionaryManagementEffectKind.ShareFile -> share(File(effect.value))
            DictionaryManagementEffectKind.ExportJson ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData(
                            "exportDictRule.json",
                            effect.value,
                            "application/json",
                        )
                }
            DictionaryManagementEffectKind.ImportText ->
                showDialogFragment(ImportDictRuleDialog(effect.value))
            DictionaryManagementEffectKind.Clipboard -> sendToClip(effect.value)
        }
    }

    override fun onPause() {
        viewModel.cancelGestures()
        super.onPause()
    }
}
