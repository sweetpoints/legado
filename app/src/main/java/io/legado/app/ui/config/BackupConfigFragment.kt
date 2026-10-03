package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

class BackupConfigFragment : Fragment(), ConfigSearchPage {
    private val model by viewModels<BackupSettingsViewModel> { viewModelFactory { initializer {
        val application = requireContext().applicationContext
        BackupSettingsViewModel(DefaultBackupSettingsRepository(AppBackupSettingsStore(application)),
            DefaultBackupChoicesRepository(AppBackupChoicesStore()), FileBackupSettingsDraftRepository(application), createSavedStateHandle())
    } } }
    private val controller by lazy { model.operations(DefaultBackupOperationsRepository(AppBackupOperationsStore(requireContext().applicationContext)),
        DefaultBackupLanRepository(AppBackupLanStore(requireContext().applicationContext))) }
    private val images by lazy { FileBackupLanImageRepository(requireContext().applicationContext) }
    private var query by mutableStateOf<String?>(null); private var selected: (() -> Unit)? = null
    private fun Uri.pathValue(): String? = if (scheme.equals("content", true)) toString() else path
    private val path = registerForActivityResult(HandleFileContract()) { result ->
        controller.result(BackupHostAction.Path, result.uri?.pathValue(), result.value ?: controller.resultId(BackupHostAction.Path))
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        controller.result(BackupHostAction.BackupDirectory, result.uri?.pathValue(), result.value ?: controller.resultId(BackupHostAction.BackupDirectory))
    }
    private val restoreFile = registerForActivityResult(HandleFileContract()) { result ->
        controller.result(BackupHostAction.RestoreFile, result.uri?.toString(), result.value ?: controller.resultId(BackupHostAction.RestoreFile))
    }
    private val importOld = registerForActivityResult(HandleFileContract()) { result ->
        controller.result(BackupHostAction.ImportOld, result.uri?.toString(), result.value ?: controller.resultId(BackupHostAction.ImportOld))
    }
    private var qrRegistry: BackupQrResultRegistry? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qrRegistry = BackupQrResultRegistry(requireActivity().activityResultRegistry) { id, value ->
            controller.result(BackupHostAction.ScanLan, value, id)
        }.also { registry -> controller.resultId(BackupHostAction.ScanLan)?.let(registry::restore) }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { LegadoComposeTheme { BackupSettingsRoute(model, controller, images, { isAdded && !parentFragmentManager.isStateSaved }, ::handle,
            { toastOnUi(R.string.backup_success) }, { toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份") }, query,
            { query = null; selected?.invoke(); selected = null }, { query = null; selected = null; toastOnUi(R.string.config_search_empty) }) } }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { super.onViewCreated(view, savedInstanceState); activity?.setTitle(R.string.backup_restore) }
    override fun searchSettings(query: String, onSelected: () -> Unit) { this.query = query; selected = onSelected }
    fun backup() = controller.backup()
    fun restore() = controller.restore()
    private fun handle(event: BackupHostEvent) {
        when (event.action) {
            BackupHostAction.Path -> path.launch { value = event.id }
            BackupHostAction.BackupDirectory -> backupDir.launch { value = event.id }
            BackupHostAction.RestoreFile -> restoreFile.launch { title = getString(R.string.select_restore_file); mode = HandleFileContract.FILE; allowExtensions = arrayOf("zip"); value = event.id }
            BackupHostAction.ImportOld -> importOld.launch { value = event.id }
            BackupHostAction.ScanLan -> qrRegistry?.launch(event.id)
            BackupHostAction.StoragePermission -> PermissionsCompat.Builder().addPermissions(*Permissions.Group.STORAGE).rationale(R.string.tip_perm_request_storage)
                .onGranted { controller.permissionResult(true, event.id) }.onDenied { controller.permissionResult(false, event.id) }.request()
            BackupHostAction.Help -> showHelp("webDavHelp")
            BackupHostAction.Log -> showDialogFragment<AppLogDialog>()
        }
    }
    override fun onStop() { controller.onStop(); val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }.onError { AppLog.put("保存备份设置草稿失败", it) }; super.onStop() }
    override fun onDestroy() {
        if (isRemoving || activity?.isFinishing == true) { val captured = model; captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }.onError { AppLog.put("清理备份设置失败", it) } }
        qrRegistry?.close(); qrRegistry = null
        selected = null; super.onDestroy()
    }
}

/** One registry key per immutable request id. Manual unregister owns the dynamic callback across rotation. */
internal class BackupQrResultRegistry(private val registry: ActivityResultRegistry, private val result: (String, String?) -> Unit) {
    private val launchers = mutableMapOf<String, ActivityResultLauncher<Unit?>>()
    private fun register(id: String): ActivityResultLauncher<Unit?>? {
        launchers[id]?.let { return it }
        var delivered = false
        val launcher = registry.register("backup-lan-scan-$id", QrCodeResult()) { value ->
            delivered = true
            launchers.remove(id)?.unregister()
            result(id, value)
        }
        // register may immediately deliver an already pending restored result.
        return if (delivered) { launcher.unregister(); null } else launcher.also { launchers[id] = it }
    }
    fun restore(id: String) { register(id) }
    fun launch(id: String) { register(id)?.launch(null) }
    fun close() { launchers.values.toList().forEach { it.unregister() }; launchers.clear() }
}
