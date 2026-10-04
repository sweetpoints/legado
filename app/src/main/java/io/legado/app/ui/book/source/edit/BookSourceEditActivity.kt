package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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

    private data class RegisteredNative(
        val id: String,
        val launch: () -> Unit,
        val unregister: () -> Unit,
    )

    private var registered: RegisteredNative? = null

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        observeEvent<Int>(PreferKey.showBoardLine) { rows -> model.keyboardRows(rows) }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BookSourceEditRoute(
            model,
            ::launchNative,
            ::registerNative,
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
        model.importText(getClipText().orEmpty())
    }

    private fun registerNative(request: BookSourceNativeRequest?) {
        if (registered?.id == request?.id) return
        registered?.unregister?.invoke()
        registered = null
        request ?: return
        val registryKey = "book-source-native-${request.id}"
        // The registry key and callback closure share the private owner UUID. Restoration uses
        // the same key, so an old result can never borrow a newer request's current UUID.
        registered =
            when (request.action) {
                BookSourceNativeAction.QR -> {
                    val launcher =
                        activityResultRegistry.register(registryKey, QrCodeResult()) { text ->
                            model.qrReturned(request.id, text)
                        }
                    RegisteredNative(request.id, { launcher.launch(null) }, launcher::unregister)
                }
                BookSourceNativeAction.FILE -> {
                    val launcher =
                        activityResultRegistry.register(registryKey, HandleFileContract()) { result
                            ->
                            val uri = result.uri
                            val text = uri?.let {
                                if (it.isContentScheme()) it.toString() else it.path
                            }
                            model.nativeInserted(request.id, request.action, text)
                        }
                    RegisteredNative(
                        request.id,
                        { launcher.launch { mode = HandleFileContract.FILE } },
                        launcher::unregister,
                    )
                }
                BookSourceNativeAction.EDITOR,
                BookSourceNativeAction.JS,
                BookSourceNativeAction.DEBUG,
                BookSourceNativeAction.LOGIN,
                BookSourceNativeAction.SEARCH -> {
                    val launcher =
                        activityResultRegistry.register(
                            registryKey,
                            ActivityResultContracts.StartActivityForResult(),
                        ) { result ->
                            when (request.action) {
                                BookSourceNativeAction.EDITOR ->
                                    model.editorReturned(
                                        request.id,
                                        result.resultCode == Activity.RESULT_OK,
                                        result.data?.getStringExtra("text"),
                                        result.data?.getStringExtra("textFile"),
                                        result.data?.getIntExtra("cursorPosition", -1) ?: -1,
                                    )
                                BookSourceNativeAction.JS ->
                                    model.jsReturned(
                                        request.id,
                                        result.resultCode == Activity.RESULT_OK,
                                        result.data?.getStringExtra("origin"),
                                    )
                                else -> model.nativeReturned(request.id, request.action)
                            }
                        }
                    RegisteredNative(
                        request.id,
                        { launcher.launch(nativeIntent(request)) },
                        launcher::unregister,
                    )
                }
                else -> null
            }
    }

    private fun nativeIntent(request: BookSourceNativeRequest): Intent {
        val target =
            when (request.action) {
                BookSourceNativeAction.EDITOR -> CodeEditActivity::class.java
                BookSourceNativeAction.JS -> JsSourceEditActivity::class.java
                BookSourceNativeAction.DEBUG -> BookSourceDebugActivity::class.java
                BookSourceNativeAction.LOGIN -> SourceLoginActivity::class.java
                BookSourceNativeAction.SEARCH -> SearchActivity::class.java
                else -> error("No native Activity for ${request.action}")
            }
        return Intent(this, target).apply {
            when (request.action) {
                BookSourceNativeAction.EDITOR -> {
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
                BookSourceNativeAction.JS -> putExtra("sourceUrl", request.sourceUrl)
                BookSourceNativeAction.DEBUG -> putExtra("key", request.sourceUrl)
                BookSourceNativeAction.LOGIN -> {
                    putExtra("type", "bookSource")
                    putExtra("key", request.sourceUrl)
                }
                BookSourceNativeAction.SEARCH -> putExtra("searchScope", request.text)
            }
        }
    }

    private fun launchNative(request: BookSourceNativeRequest) {
        when (request.action) {
            BookSourceNativeAction.EDITOR,
            BookSourceNativeAction.JS,
            BookSourceNativeAction.DEBUG,
            BookSourceNativeAction.LOGIN,
            BookSourceNativeAction.SEARCH,
            BookSourceNativeAction.QR,
            BookSourceNativeAction.FILE -> {
                check(registered?.id == request.id) { "Missing native request owner" }
                registered!!.launch()
            }
            BookSourceNativeAction.COPY -> {
                sendToClip(request.text.orEmpty())
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.SHARE -> {
                share(request.text.orEmpty())
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.QR_SHARE -> {
                shareWithQr(
                    request.text.orEmpty(),
                    getString(R.string.share_book_source),
                    ErrorCorrectionLevel.L,
                )
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.HELP -> {
                showHelp(request.text.orEmpty())
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.LOG -> {
                showDialogFragment<AppLogDialog>()
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.KEYBOARD_CONFIG -> {
                showDialogFragment<KeyboardAssistsConfig>()
                model.nativeReturned(request.id, request.action)
            }
            BookSourceNativeAction.URL_OPTIONS ->
                UrlOptionDialog(this) { model.nativeInserted(request.id, request.action, it) }
                    .apply {
                        setOnDismissListener { model.nativeReturned(request.id, request.action) }
                    }
                    .show()
        }
    }

    override fun onDestroy() {
        registered?.unregister?.invoke()
        registered = null
        super.onDestroy()
    }

    private fun recordSaved(origin: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra("origin", origin))
    }

    private fun complete(origin: String?) {
        if (origin != null) recordSaved(origin)
        super.finish()
    }
}
