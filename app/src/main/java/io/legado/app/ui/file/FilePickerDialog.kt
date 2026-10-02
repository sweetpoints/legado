package io.legado.app.ui.file

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.DiskLocalFilePickerRepository
import io.legado.app.data.repository.LocalFilePickerConfig
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.utils.setLayout
import java.io.File

class FilePickerDialog : BaseComposeDialogFragment() {
    companion object {
        const val tag = "FileChooserDialog"
        fun show(manager: FragmentManager, mode: Int = FILE, title: String? = null,
            initPath: String? = null, isShowHideDir: Boolean = false, allowExtensions: Array<String>? = null) {
            FilePickerDialog().apply { arguments = Bundle().apply {
                putInt("mode", mode); putString("title", title); putString("initPath", initPath)
                putBoolean("isShowHideDir", isShowHideDir); putStringArray("allowExtensions", allowExtensions)
            } }.show(manager, tag)
        }
    }
    internal val model by viewModels<LocalFilePickerViewModel> {
        viewModelFactory { initializer {
            @Suppress("DEPRECATION")
            val root = arguments?.getString("initPath") ?: Environment.getExternalStorageDirectory().path
            LocalFilePickerViewModel(DiskLocalFilePickerRepository(), createSavedStateHandle(), LocalFilePickerConfig(root,
                arguments?.getInt("mode", FILE) == HandleFileContract.DIR,
                arguments?.getStringArray("allowExtensions")?.toList().orEmpty(), arguments?.getBoolean("isShowHideDir") == true))
        } }
    }
    override fun onStart() { super.onStart(); setLayout(0.9f, 0.8f) }
    @Composable override fun Content() {
        val title = arguments?.getString("title") ?: getString(if (model.config.selectDirectory) R.string.folder_chooser else R.string.file_chooser)
        LocalFilePickerRoute(model, title, { isAdded && !parentFragmentManager.isStateSaved }, ::deliver, ::dismissAllowingStateLoss, { isCancelable = it })
    }
    private fun deliver(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        (parentFragment as? CallBack)?.onResult(data)
        (activity as? CallBack)?.onResult(data)
    }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
    override fun onDismiss(dialog: DialogInterface) { super.onDismiss(dialog); activity?.takeUnless { it.isChangingConfigurations }?.finish() }
    interface CallBack { fun onResult(data: Intent) }
}
