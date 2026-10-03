package io.legado.app.ui.book.source.manage

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.edit.JsSourceEditActivity
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native launchers and the existing check service are the only activity-owned effects. */
class BookSourceActivity : BaseComposeActivity() {
    internal val managerModel by
        viewModels<BookSourceManagerViewModel> {
            viewModelFactory {
                initializer {
                    val token = BookSourceManagerViewModel.token(createSavedStateHandle())
                    BookSourceManagerViewModel(
                        AppBookSourceManagerRepository(applicationContext),
                        SourceManagerSessionStore(applicationContext, token),
                    )
                }
            }
        }
    private var checkSessionId: Long? = null
    private val qrResult =
        registerForActivityResult(QrCodeResult()) { result ->
            result?.let { managerModel.effect("import", it) }
        }
    private val importDocument =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { managerModel.effect("import", it.toString()) }
        }
    private val exportDirectory =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { uri ->
                val url = uri.toString()
                alert(R.string.export_success) {
                    if (url.isAbsUrl()) {
                        setMessage(DirectLinkUpload.getSummary())
                        sourceSharePassphraseButton(
                            layoutInflater,
                            url,
                            SourceSharePassphrase.Type.BOOK_SOURCE,
                        )
                    }
                    val editBinding =
                        DialogEditTextBinding.inflate(layoutInflater).apply {
                            editView.hint = getString(R.string.path)
                            editView.setText(url)
                        }
                    customView { editBinding.root }
                    okButton { sendToClip(url) }
                }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (!LocalConfig.bookSourcesHelpVersionIsLast) showHelp("SourceMBookHelp")
        if (Debug.isChecking) {
            checkSessionId = Debug.currentCheckSession()
            CheckSource.resume(this)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val checking = Debug.isChecking
                    keepScreenOn(checking)
                    managerModel.debugMessages(
                        if (checking) Debug.debugMessageMap.toMap() else emptyMap()
                    )
                    if (!checking) managerModel.checkProgress(null)
                    delay(300)
                }
            }
        }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BookSourceManagerRoute(
            model = managerModel,
            onEffect = ::handleEffect,
            canHandleEffect = { !supportFragmentManager.isStateSaved },
        )
    }

    private suspend fun handleEffect(effect: SourceManagerEffect) {
        when (effect.action) {
            "back" -> finish()
            "add" -> startActivity<BookSourceEditActivity>()
            "add-js" -> startActivity<JsSourceEditActivity>()
            "qr" -> qrResult.launch()
            "local" ->
                importDocument.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json", "js")
                }
            "import" -> showDialogFragment(ImportBookSourceDialog(effect.key))
            "groups" -> showDialogFragment<GroupManageDialog>()
            "check-config" -> showDialogFragment<CheckSourceConfig>()
            "help" -> showHelp("SourceMBookHelp")
            "edit" -> startActivity<BookSourceEditActivity> { putExtra("sourceUrl", effect.key) }
            "debug" -> startActivity<BookSourceDebugActivity> { putExtra("key", effect.key) }
            "login" ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", effect.key)
                }
            "search" ->
                managerModel.checkSources(listOf(effect.key)).singleOrNull()?.let {
                    SearchActivity.start(this, it)
                }
            "cancel-check" -> checkSessionId?.let { CheckSource.stop(this, it) }
            "check" -> startCheck(effect)
            "export" ->
                effect.export?.let { output ->
                    exportDirectory.launch {
                        mode = HandleFileContract.EXPORT
                        fileData =
                            HandleFileContract.FileData(output.name, output.file, output.mime)
                    }
                }
            "share" ->
                effect.export?.let { output ->
                    share(
                        output.file,
                        if (output.mime == "text/javascript") output.mime else "text/*",
                    )
                }
        }
    }

    private suspend fun startCheck(effect: SourceManagerEffect) {
        val sources = managerModel.checkSources(effect.keys)
        if (sources.isEmpty()) return
        val sessionId = Debug.tryStartCheckSession()
        if (sessionId == null) {
            managerModel.hostError("书源调试通道占用中，请稍后重试")
            return
        }
        if (effect.key.isNotEmpty()) CheckSource.keyword = effect.key
        // Once accepted, finishing the service handoff must survive lifecycle cancellation.
        withContext(NonCancellable) {
            CheckSource.start(this@BookSourceActivity, sources, sessionId)
            checkSessionId = sessionId
            keepScreenOn(true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Long, String>>(EventBus.CHECK_SOURCE) { (sessionId, message) ->
            if (Debug.isChecking(sessionId)) {
                checkSessionId = sessionId
                managerModel.checkProgress(message)
            }
        }
        observeEvent<Long>(EventBus.CHECK_SOURCE_DONE) { sessionId ->
            if (checkSessionId == null || checkSessionId == sessionId) {
                checkSessionId = null
                keepScreenOn(false)
                managerModel.checkProgress(null)
                managerModel.debugMessages(emptyMap())
            }
        }
    }

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) Debug.debugMessageMap.clear()
    }
}
