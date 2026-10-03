package io.legado.app.ui.font

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppFontSelectionRepository
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.setLayout
import java.io.File

class FontSelectDialog : BaseComposeDialogFragment() {
    private val viewModel by
        viewModels<FontSelectViewModel> {
            viewModelFactory {
                initializer {
                    FontSelectViewModel(
                        AppFontSelectionRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val selectFontDir =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { uri ->
                val folder = if (uri.isContentScheme()) uri.toString() else uri.path
                folder?.let {
                    viewModel.rememberFolder(it)
                    loadFolder(it)
                }
            }
        }
    private val importFont =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.importFont(it.toString()) }
        }

    override fun onStart() {
        super.onStart()
        setLayout(.9f, .9f)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        loadFolder(viewModel.repository.storedFolder(), openWhenEmpty = true)
    }

    private fun loadFolder(folder: String?, openWhenEmpty: Boolean = false) {
        if (folder.isNullOrEmpty() || folder.isContentScheme())
            viewModel.load(folder, openWhenEmpty)
        else
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
                .onGranted { if (isAdded) viewModel.load(folder, openWhenEmpty) }
                .onDenied { if (isAdded) viewModel.load(null) }
                .request()
    }

    @Composable
    override fun Content() {
        FontSelectRoute(
            viewModel,
            callBack?.curFontPath.orEmpty(),
            { viewModel.defaultFont(shouldSelectSystemTypeface(callBack)) },
            {
                if (isAdded && !parentFragmentManager.isStateSaved) {
                    selectFontDir.launch {
                        otherActions = arrayListOf(SelectItem("SD${File.separator}Fonts", -1))
                    }
                    true
                } else false
            },
            {
                importFont.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.import_str)
                }
            },
            ::finishSelection,
            { dismissAllowingStateLoss() },
        )
    }

    private fun finishSelection(path: String): Boolean {
        if (!isAdded || parentFragmentManager.isStateSaved) return false
        callBack?.selectFont(path)
        dismissAllowingStateLoss()
        return true
    }

    /** Compatibility entry point; row and default callbacks execute on the UI thread. */
    fun onFontSelect(docItem: FileDoc) {
        finishSelection(docItem.toString())
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)

        val curFontPath: String
        val selectSystemTypefaceOnDefault: Boolean
            get() = true
    }

    companion object {
        internal fun shouldSelectSystemTypeface(callBack: CallBack?) =
            callBack?.selectSystemTypefaceOnDefault != false
    }
}
