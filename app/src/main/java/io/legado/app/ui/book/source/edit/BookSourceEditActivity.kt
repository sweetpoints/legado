package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.getClipText
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

/** Platform results and effects only. Source payloads and edit ownership live in private drafts. */
class BookSourceEditActivity : BaseComposeActivity() {
    private val model by
        viewModels<BookSourceComposeViewModel> {
            viewModelFactory {
                initializer {
                    BookSourceComposeViewModel(
                        RoomBookSourceEditorRepository(applicationContext),
                        createSavedStateHandle(),
                        intent.getStringExtra("sourceUrl"),
                    )
                }
            }
        }
    private val jsSourceEdit =
        registerForActivityResult(StartActivityContract(JsSourceEditActivity::class.java)) { result
            ->
            model.jsReturned(
                result.resultCode == Activity.RESULT_OK,
                result.data?.getStringExtra("origin"),
            )
        }
    private val qrCodeResult =
        registerForActivityResult(QrCodeResult()) { text ->
            if (text == null) model.nativeReturned(BookSourceNativeAction.QR)
            else model.importText(text)
        }
    private val selectDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            val uri = result.uri
            val text = uri?.let { if (it.isContentScheme()) it.toString() else it.path }
            model.nativeInserted(BookSourceNativeAction.FILE, text)
        }
    private val textEditLauncher =
        registerForActivityResult(StartActivityContract(CodeEditActivity::class.java)) { result ->
            model.editorReturned(
                result.resultCode == Activity.RESULT_OK,
                result.data?.getStringExtra("text"),
                result.data?.getStringExtra("textFile"),
                result.data?.getIntExtra("cursorPosition", -1) ?: -1,
            )
        }
    private val debugResult =
        registerForActivityResult(StartActivityContract(BookSourceDebugActivity::class.java)) {
            model.nativeReturned(BookSourceNativeAction.DEBUG)
        }
    private val loginResult =
        registerForActivityResult(StartActivityContract(SourceLoginActivity::class.java)) {
            model.nativeReturned(BookSourceNativeAction.LOGIN)
        }
    private val searchResult =
        registerForActivityResult(StartActivityContract(SearchActivity::class.java)) {
            model.nativeReturned(BookSourceNativeAction.SEARCH)
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        observeEvent<Int>(PreferKey.showBoardLine) { rows -> model.keyboardRows(rows) }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BookSourceEditRoute(
            model,
            ::launchNative,
            ::complete,
            ::recordSaved,
            ::paste,
            canHandle = { !isFinishing && !isDestroyed },
            initialHelpNeeded = !LocalConfig.ruleHelpVersionIsLast,
            maxLines = AppConfig.sourceEditMaxLine,
            keyboardRows = AppConfig.showBoardLine,
            transparentBackground = transparentNavBar && !AppConfig.isEInkMode,
        )
    }

    private fun paste() {
        val text = getClipText()
        model.importText(text.orEmpty())
    }

    private fun launchNative(request: BookSourceNativeRequest) {
        when (request.action) {
            BookSourceNativeAction.EDITOR ->
                textEditLauncher.launch {
                    putExtra("textFile", request.path)
                    putExtra("useTextFile", true)
                    putExtra("cursorPosition", request.cursor)
                    val field =
                        request.key?.let {
                            model.state.value.document?.form?.field(request.tab, it)
                        }
                    putExtra(
                        "title",
                        field?.label ?: field?.labelResource?.takeIf { it != 0 }?.let(::getString),
                    )
                }
            BookSourceNativeAction.JS ->
                jsSourceEdit.launch { putExtra("sourceUrl", request.sourceUrl) }
            BookSourceNativeAction.DEBUG ->
                debugResult.launch { putExtra("key", request.sourceUrl) }
            BookSourceNativeAction.LOGIN ->
                loginResult.launch {
                    putExtra("type", "bookSource")
                    putExtra("key", request.sourceUrl)
                }
            BookSourceNativeAction.SEARCH ->
                searchResult.launch { putExtra("searchScope", request.text) }
            BookSourceNativeAction.QR -> qrCodeResult.launch()
            BookSourceNativeAction.FILE -> selectDoc.launch { mode = HandleFileContract.FILE }
            BookSourceNativeAction.COPY -> {
                sendToClip(request.text.orEmpty())
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.SHARE -> {
                share(request.text.orEmpty())
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.QR_SHARE -> {
                shareWithQr(
                    request.text.orEmpty(),
                    getString(R.string.share_book_source),
                    ErrorCorrectionLevel.L,
                )
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.HELP -> {
                showHelp(request.text.orEmpty())
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.LOG -> {
                showDialogFragment<AppLogDialog>()
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.KEYBOARD_CONFIG -> {
                showDialogFragment<KeyboardAssistsConfig>()
                model.nativeReturned(request.action)
            }
            BookSourceNativeAction.URL_OPTIONS -> {
                UrlOptionDialog(this) { model.nativeInserted(request.action, it) }
                    .apply {
                        setOnDismissListener { model.nativeReturned(request.action) }
                    }
                    .show()
            }
        }
    }

    private fun recordSaved(origin: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra("origin", origin))
    }

    private fun complete(origin: String?) {
        if (origin != null) setResult(Activity.RESULT_OK, Intent().putExtra("origin", origin))
        super.finish()
    }
}
