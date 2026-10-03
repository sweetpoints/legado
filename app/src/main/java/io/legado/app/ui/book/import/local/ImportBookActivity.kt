package io.legado.app.ui.book.import.local

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
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.startActivityForBook
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** Native file picker and storage permission bridge for the Compose local import workflow. */
class ImportBookActivity : BaseComposeActivity() {
    private val model by
        viewModels<LocalImportViewModel> {
            viewModelFactory {
                initializer {
                    val saved = createSavedStateHandle()
                    val restored = saved.contains(SESSION_KEY)
                    val ticket =
                        saved.get<String>(SESSION_KEY)
                            ?: UUID.randomUUID().toString().also { saved[SESSION_KEY] = it }
                    saved.keys().filter { it != SESSION_KEY }.forEach { saved.remove<Any>(it) }
                    LocalImportViewModel(
                        LocalImportRepository(applicationContext),
                        LocalImportSession(
                            ticket,
                            FileLocalImportSessions(File(filesDir, "local-import-sessions")),
                            !restored,
                        ),
                    )
                }
            }
        }
    private val pickers by lazy {
        LocalImportNativeRegistry(activityResultRegistry) { receipt, result ->
            when (receipt.kind) {
                LocalImportNativeKind.Folder ->
                    model.pickedFolder(receipt.nonce, result.uri?.toString())
                LocalImportNativeKind.Storage ->
                    model.pickedStorage(receipt.nonce, result.uri?.toString())
                else -> Unit
            }
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { if (!model.back()) finish() }
        lifecycleScope.launch {
            model.state.collect { current -> current.registryNative?.let(pickers::register) }
        }
        model.initialize()
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        LocalImportRoute(model, { if (!model.back()) finish() }, ::deliverNative)
    }

    private fun deliverNative(receipt: LocalImportNative) {
        when (receipt.kind) {
            LocalImportNativeKind.Folder -> {
                pickers.register(receipt).launch({ value = receipt.nonce })
            }
            LocalImportNativeKind.Storage -> {
                pickers
                    .register(receipt)
                    .launch({
                        mode = HandleFileContract.DIR_SYS
                        title = getString(R.string.local_book_save_path)
                        value = receipt.nonce
                    })
            }
            LocalImportNativeKind.Permission ->
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted { model.permissionResult(receipt.nonce, true) }
                    .onDenied { model.permissionResult(receipt.nonce, false) }
                    .onError { model.permissionResult(receipt.nonce, false) }
                    .request()
            LocalImportNativeKind.Read ->
                receipt.book?.let {
                    startActivityForBook(it)
                    model.nativeComplete(receipt.nonce)
                }
        }
    }

    override fun onDestroy() {
        pickers.close()
        super.onDestroy()
    }

    private companion object {
        const val SESSION_KEY = "localImport.session"
    }
}
