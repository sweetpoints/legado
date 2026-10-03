package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AppRemoteLibraryReadingStore
import io.legado.app.data.repository.AppRemoteLibraryStore
import io.legado.app.data.repository.DefaultRemoteLibraryReadingRepository
import io.legado.app.data.repository.DefaultRemoteLibraryRepository
import io.legado.app.data.repository.FileRemoteLibraryDraftRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.remote.RemoteLibraryEffect
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

/** Native file selection and reading remain host effects; all page controls are Compose. */
class RemoteBookActivity : BaseComposeActivity(), ServersDialog.Callback {
    internal val model by
        viewModels<RemoteLibraryViewModel> {
            viewModelFactory {
                initializer {
                    val application = applicationContext
                    RemoteLibraryViewModel(
                        DefaultRemoteLibraryRepository(AppRemoteLibraryStore()),
                        DefaultRemoteLibraryReadingRepository(
                            AppRemoteLibraryReadingStore(application)
                        ),
                        FileRemoteLibraryDraftRepository(application),
                        createSavedStateHandle(),
                        { message, error -> AppLog.put(message, error) },
                    )
                }
            }
        }
    private var storageTicket: String? = null
    private val folder =
        registerForActivityResult(HandleFileContract()) { result ->
            val id = result.value ?: storageTicket
            if (id != null) model.storagePicked(id, result.uri?.toString())
            if (id == storageTicket) storageTicket = null
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        storageTicket = savedInstanceState?.getString("remoteStorageTicket")
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        RemoteLibraryRoute(
            model,
            { !isFinishing && !supportFragmentManager.isStateSaved },
            ::handle,
            ::finish,
        )
    }

    private fun handle(prepared: PreparedRemoteLibraryEffect) {
        val receipt = prepared.receipt
        when (receipt.effect) {
            RemoteLibraryEffect.PickStorage -> {
                storageTicket = receipt.id
                folder.launch {
                    title = getString(R.string.select_book_folder)
                    value = receipt.id
                }
            }
            RemoteLibraryEffect.OpenBook -> prepared.book?.let { startActivityForBook(it) }
            RemoteLibraryEffect.Servers ->
                if (supportFragmentManager.findFragmentByTag("ServersDialog") == null)
                    showDialogFragment<ServersDialog>()
            RemoteLibraryEffect.Log ->
                if (supportFragmentManager.findFragmentByTag("AppLogDialog") == null)
                    showDialogFragment<AppLogDialog>()
            RemoteLibraryEffect.Help -> showHelp("webDavBookHelp")
            RemoteLibraryEffect.Toast ->
                if (receipt.resource != 0) toastOnUi(receipt.resource)
                else receipt.text?.let { toastOnUi(it) }
            RemoteLibraryEffect.Close -> finish()
        }
    }

    override fun onDialogDismiss(tag: String) {
        if (!isFinishing && !isChangingConfigurations) model.serverChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("remoteStorageTicket", storageTicket)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }
            .onError { AppLog.put("保存远程书籍草稿失败", it) }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }
                .onError { AppLog.put("清理远程书籍草稿失败", it) }
        }
        super.onDestroy()
    }
}
