package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AppHandleFileChoicesRepository
import io.legado.app.data.repository.FileHandleFileChoicesSessionRepository
import io.legado.app.data.repository.HandleFileInput
import io.legado.app.data.repository.HandleFileIssue
import io.legado.app.data.repository.HandleFileSeed
import io.legado.app.help.IntentData
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun takePersistableUriPermissions(
    requestedFlags: Int,
    takePermission: (Int) -> Unit,
): Int {
    var persistedFlags = 0
    listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        .forEach { flag ->
            if (requestedFlags and flag != 0) {
                try {
                    takePermission(flag)
                    persistedFlags = persistedFlags or flag
                } catch (_: SecurityException) {}
            }
        }
    return persistedFlags
}

class HandleFileActivity : BaseComposeActivity(transparent = true), FilePickerDialog.CallBack {
    private val repository by lazy { AppHandleFileChoicesRepository(applicationContext) }
    internal val choicesModel by
        viewModels<HandleFileChoicesViewModel> {
            viewModelFactory {
                initializer {
                    val saved =
                        createSavedStateHandle().apply {
                            // Intent defaults contain unrestricted labels/URLs. Only private UUID
                            // ownership
                            // and a small revision may enter the framework's saved-state Bundle.
                            keys()
                                .filterNot { it.startsWith("handleFile.") }
                                .forEach { remove<Any?>(it) }
                        }
                    HandleFileChoicesViewModel(
                        saved,
                        repository,
                        FileHandleFileChoicesSessionRepository(),
                    )
                }
            }
        }
    private val directoryLaunchers = mutableMapOf<String, ActivityResultLauncher<Uri?>>()
    private val documentLaunchers = mutableMapOf<String, ActivityResultLauncher<Array<String>>>()
    private val imageLaunchers = mutableMapOf<String, ActivityResultLauncher<Int?>>()
    private val permissionRequests = mutableSetOf<String>()
    private var completing = false

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val input =
                HandleFileInput(
                    mode = intent.getIntExtra("mode", 0),
                    title = intent.getStringExtra("title"),
                    extensions = intent.getStringArrayExtra("allowExtensions")?.toList().orEmpty(),
                    fileName = intent.getStringExtra("fileName"),
                    contentType = intent.getStringExtra("contentType"),
                    value = intent.getStringExtra("value"),
                )
            val payload = intent.getStringExtra("fileKey")?.let { IntentData.get<Any>(it) }
            choicesModel.load(HandleFileSeed(input, payload, intent.getStringExtra("otherActions")))
        } else {
            choicesModel.load()
        }
        lifecycleScope.launch {
            choicesModel.state.collect { state ->
                val pending = state.pending ?: return@collect
                // Register even delivered launches: ActivityResultRegistry retains early results
                // until asynchronous private checkpoint loading establishes their exact nonce.
                if (state.loaded && state.phase == "Native")
                    registerPicker(pending.nonce, pending.action)
            }
        }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        HandleFileChoicesRoute(
            model = choicesModel,
            canDeliver = ::canDeliver,
            native = ::launchNative,
            result = ::completeResult,
            close = ::completeCancellation,
        )
    }

    private fun canDeliver(): Boolean =
        !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState == Lifecycle.State.RESUMED &&
            !supportFragmentManager.isStateSaved

    private fun registerPicker(nonce: String, action: Int) {
        when (action) {
            0 ->
                directoryLaunchers.getOrPut(nonce) {
                    activityResultRegistry.register(
                        "handle-directory-$nonce",
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri ->
                        takePermissions(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        choicesModel.returned(nonce, uri?.toString())
                    }
                }
            1 ->
                documentLaunchers.getOrPut(nonce) {
                    activityResultRegistry.register(
                        "handle-document-$nonce",
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        takePermissions(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        choicesModel.returned(nonce, uri?.toString())
                    }
                }
            4 ->
                imageLaunchers.getOrPut(nonce) {
                    activityResultRegistry.register("handle-image-$nonce", SelectImageContract()) {
                        result ->
                        choicesModel.returned(nonce, result.uri?.toString())
                    }
                }
        }
    }

    private fun launchNative(request: HandleFileNativeRequest): Boolean {
        val nonce = request.nonce
        val action = request.action
        registerPicker(nonce, action)
        val current = choicesModel.state.value
        if (!canDeliver() || current.phase != "Native" || current.pending?.nonce != nonce)
            return false
        when (action) {
            0 -> launchSystemPicker(nonce) { directoryLaunchers.getValue(nonce).launch(null) }
            1 ->
                launchSystemPicker(nonce) {
                    documentLaunchers.getValue(nonce).launch(request.mimeTypes.toTypedArray())
                }
            4 -> imageLaunchers.getValue(nonce).launch(null)
            10,
            11,
            112,
            113 -> requestPermissions(nonce, action)
            else -> {
                val path = request.customPath
                val uri = if (path.isContentScheme()) Uri.parse(path) else Uri.fromFile(File(path))
                choicesModel.returned(nonce, uri.toString())
            }
        }
        return true
    }

    private fun launchSystemPicker(nonce: String, launch: () -> Unit) {
        runCatching(launch).onFailure {
            AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
            choicesModel.fallback(nonce)
        }
    }

    private fun requestPermissions(nonce: String, action: Int) {
        if (!permissionRequests.add(nonce)) return
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                lifecycleScope.launch {
                    lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                    val state = choicesModel.state.value
                    if (!canDeliver() || state.phase != "Native" || state.pending?.nonce != nonce)
                        return@launch
                    choicesModel.state.first { !it.busy }
                    if (action == 112 || action == 113) choicesModel.manualReady(nonce)
                    else showAppPicker(nonce, action)
                }
            }
            .onDenied { choicesModel.returned(nonce, null) }
            .onError { choicesModel.returned(nonce, null) }
            .request()
    }

    private fun showAppPicker(nonce: String, action: Int) {
        if (supportFragmentManager.findFragmentByTag(FilePickerDialog.tag) != null) return
        val state = choicesModel.state.value
        if (!canDeliver() || state.phase != "Native" || state.pending?.nonce != nonce) return
        FilePickerDialog()
            .apply {
                arguments =
                    Bundle().apply {
                        putInt(
                            "mode",
                            if (action == 10) HandleFileContract.DIR else HandleFileContract.FILE,
                        )
                        putStringArray("allowExtensions", state.input?.extensions?.toTypedArray())
                        putString("handleFileNonce", nonce)
                    }
            }
            .show(supportFragmentManager, FilePickerDialog.tag)
    }

    override fun onResult(data: Intent) {
        val picker = supportFragmentManager.findFragmentByTag(FilePickerDialog.tag)
        val nonce = picker?.arguments?.getString("handleFileNonce") ?: return
        choicesModel.returned(nonce, data.data?.toString())
    }

    private fun takePermissions(uri: Uri?, flags: Int) {
        if (uri == null || !uri.isContentScheme()) return
        takePersistableUriPermissions(flags) {
            contentResolver.takePersistableUriPermission(uri, it)
        }
    }

    private fun completeResult(uri: String, value: String?) {
        completing = true
        val result = Intent().setData(Uri.parse(uri))
        if (choicesModel.state.value.input?.mode != HandleFileContract.EXPORT)
            result.putExtra("value", value)
        setResult(RESULT_OK, result)
        super.finish()
    }

    private fun completeCancellation(issue: HandleFileIssue?) {
        issue?.let {
            val resource =
                when (it) {
                    HandleFileIssue.EmptyDirectory -> R.string.empty_directory_input
                    HandleFileIssue.InvalidDirectory -> R.string.invalid_directory
                    HandleFileIssue.EmptyImage -> R.string.empty_img_src_input
                    HandleFileIssue.InvalidImage -> R.string.invalid_file_path
                    HandleFileIssue.PayloadMissing -> R.string.error
                }
            toastOnUi(getString(resource))
        }
        completing = true
        super.finish()
    }

    override fun finish() {
        // FilePickerDialog closes its Activity on dismissal. An accepted asynchronous export must
        // finish only after its private result receipt; close() ignores that busy interval.
        if (completing || isChangingConfigurations) super.finish() else choicesModel.close()
    }

    override fun onDestroy() {
        directoryLaunchers.values.forEach { it.unregister() }
        documentLaunchers.values.forEach { it.unregister() }
        imageLaunchers.values.forEach { it.unregister() }
        super.onDestroy()
    }
}
