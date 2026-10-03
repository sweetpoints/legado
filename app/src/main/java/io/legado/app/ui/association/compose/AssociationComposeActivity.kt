package io.legado.app.ui.association.compose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationLaunchRepository
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationNativeReceipt
import io.legado.app.data.association.AssociationNativeResult
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDataDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportLocalBookDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportThemeDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.association.showImportHighlightRuleDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared transparent Compose host for the public file and online import entries. */
abstract class AssociationComposeActivity :
    BaseComposeActivity(transparent = true, imageBg = false) {
    protected abstract val hostKind: AssociationHostKind
    protected abstract val importModelClass: Class<out AssociationImportViewModel>

    protected abstract fun createImportModel(
        savedState: SavedStateHandle
    ): AssociationImportViewModel

    internal val importModel: AssociationImportViewModel
        get() = ViewModelProvider(this)[importModelClass]

    private val dependencies by lazy { AssociationDependencies(application) }
    private var configuredDirectory by mutableStateOf<String?>(null)
    private var pickerOwner: String? = null
    private var privateOwnerVerified by mutableStateOf(false)
    private var privateOwnerAccepted = false
    private val directoryPicker =
        registerForActivityResult(HandleFileContract()) { result ->
            val owner = result.value ?: pickerOwner ?: return@registerForActivityResult
            val parts = owner.split(':')
            if (parts.size != 3 || parts[2].toLongOrNull() == null) return@registerForActivityResult
            val ticket = parts[0]
            if (ticket != intent.getStringExtra(AssociationImportViewModel.TICKET_KEY)) {
                return@registerForActivityResult
            }
            cleanupScope.launch {
                runCatching {
                    val receipt =
                        dependencies.sessions.read(ticket).claimedEffects.firstOrNull {
                            it.kind == AssociationNativeKind.SelectDirectory &&
                                it.token == parts[1] &&
                                it.generation == parts[2].toLong()
                        } ?: return@runCatching
                    val accepted =
                        dependencies.nativeResults.record(
                            ticket,
                            AssociationNativeResult(receipt, directory = result.uri?.toString()),
                        )
                    if (accepted && result.uri != null) {
                        withContext(Dispatchers.IO) {
                            AppConfig.defaultBookTreeUri = result.uri.toString()
                        }
                    }
                    if (!isFinishing && !isDestroyed) importModel.reconcileNativeResults()
                }
                    .onFailure { if (!isFinishing && !isDestroyed) toastOnUi(it.localizedMessage) }
            }
        }

    override val defaultViewModelCreationExtras: CreationExtras
        get() = associationCreationExtras(super.defaultViewModelCreationExtras)

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() =
            AssociationViewModelFactory(
                super.defaultViewModelProviderFactory,
                importModelClass,
                { intent.getStringExtra(AssociationImportViewModel.TICKET_KEY) },
                ::createImportModel,
            )

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        pickerOwner = savedInstanceState?.getString(PICKER_OWNER_KEY)
        val model = importModel
        lifecycleScope.launch {
            configuredDirectory = withContext(Dispatchers.IO) { AppConfig.defaultBookTreeUri }
            if (
                model.state.value.ticket != null ||
                    intent.hasExtra(AssociationImportViewModel.TICKET_KEY)
            )
                return@launch
            var prepared: String? = null
            try {
                val ticket =
                    AssociationLaunchRepository(application, dependencies.sessions)
                        .prepare(Intent(intent), hostKind)
                prepared = ticket
                currentCoroutineContext().ensureActive()
                if (isFinishing || isDestroyed) return@launch
                // Transfer complete launch ownership before clearing Android's default extras.
                model.attachPrepared(ticket)
                // A freshly allocated ticket is ours even if destruction interrupts inspection.
                // Restored/external tickets acquire cleanup ownership only after host validation.
                privateOwnerAccepted = model.ownedTicket == ticket
                check(privateOwnerAccepted) { "Import session adoption was rejected" }
                // UUID adoption is durable, but a restored ticket is verified by the common
                // state collector before either launch path clears the original Intent body.
                prepared = null
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                toastOnUi(failure.localizedMessage)
                finish()
            } finally {
                prepared?.let { withContext(NonCancellable) { dependencies.sessions.release(it) } }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collectLatest { current ->
                    if (!current.loaded || current.busy || current.nativeResultPending)
                        return@collectLatest
                    val session = current.session ?: return@collectLatest
                    if (current.ticket != model.ownedTicket || session.input.host != hostKind) {
                        toastOnUi("导入会话与当前入口不匹配")
                        finish()
                        return@collectLatest
                    }
                    privateOwnerAccepted = true
                    privateOwnerVerified = true
                    normalizeOwnedIntent(checkNotNull(current.ticket))
                    if (
                        session.phase == AssociationPhase.Finished &&
                            session.completionMessage == null &&
                            session.effects.isEmpty()
                    ) {
                        finish()
                    } else if (hostKind == AssociationHostKind.File && session.error != null) {
                        toastOnUi(session.error)
                        delay(2_000)
                        finish()
                    } else if (
                        session.phase == AssociationPhase.Preview &&
                            session.previews.isNotEmpty() &&
                            session.effects.isEmpty() &&
                            supportFragmentManager.findFragmentByTag(LOCAL_PREVIEW_TAG) == null &&
                            !supportFragmentManager.isStateSaved
                    ) {
                        ImportLocalBookDialog().show(supportFragmentManager, LOCAL_PREVIEW_TAG)
                    } else if (
                        session.phase == AssociationPhase.Directory &&
                            session.importAfterDirectory &&
                            !session.choosingDirectory &&
                            !configuredDirectory.isNullOrBlank()
                    ) {
                        model.confirmOperation("local-import", configuredDirectory)
                    }
                }
            }
        }
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        val state by importModel.state.collectAsStateWithLifecycle()
        if (!privateOwnerVerified && state.restoreError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("association-loading"))
            }
            return
        }
        AssociationImportRoute(
            model = importModel,
            configuredDirectory = configuredDirectory,
            privateDirectory = Uri.fromFile(File(filesDir, "books")).toString(),
            canDeliver = {
                privateOwnerVerified &&
                    !isFinishing &&
                    !isDestroyed &&
                    !supportFragmentManager.isStateSaved
            },
            prepare = ::prepareDelivery,
            onDeliveryError = { toastOnUi(it.localizedMessage) },
            onChoosePrivateDirectory = {
                val directory = Uri.fromFile(File(filesDir, "books")).toString()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { AppConfig.defaultBookTreeUri = directory }
                    configuredDirectory = directory
                    if (importModel.state.value.session?.importAfterDirectory == true) {
                        importModel.confirmOperation("local-import", directory)
                    } else importModel.cancelDirectory()
                }
            },
            onClose = ::finish,
            showSessionErrors = hostKind == AssociationHostKind.Online,
        )
    }

    private fun normalizeOwnedIntent(ticket: String) {
        if (
            intent.data == null &&
                intent.clipData == null &&
                intent.extras?.keySet() == setOf(AssociationImportViewModel.TICKET_KEY) &&
                intent.getStringExtra(AssociationImportViewModel.TICKET_KEY) == ticket
        )
            return
        intent =
            Intent(this, javaClass)
                .addFlags(intent.flags)
                .putExtra(AssociationImportViewModel.TICKET_KEY, ticket)
    }

    private suspend fun prepareDelivery(
        receipt: AssociationNativeReceipt
    ): PreparedAssociationDelivery {
        val ticket = checkNotNull(importModel.state.value.ticket)
        return when (receipt.kind) {
            AssociationNativeKind.StoragePermission ->
                PreparedAssociationDelivery(
                    {
                        PermissionsCompat.Builder()
                            .addPermissions(*Permissions.Group.STORAGE)
                            .rationale(R.string.tip_perm_request_storage)
                            .onGranted { recordPermission(ticket, receipt, true) }
                            .onDenied { recordPermission(ticket, receipt, false) }
                            .request()
                    },
                    awaitsResult = true,
                )
            AssociationNativeKind.SelectDirectory ->
                PreparedAssociationDelivery(
                    {
                        val owner = "$ticket:${receipt.token}:${receipt.generation}"
                        pickerOwner = owner
                        directoryPicker.launch {
                            title = getString(R.string.select_book_folder)
                            mode = HandleFileContract.DIR_SYS
                            value = owner
                        }
                    },
                    awaitsResult = true,
                )
            AssociationNativeKind.ImportDialog ->
                PreparedAssociationDelivery({ showImport(receipt) })
            AssociationNativeKind.OnlineImport -> {
                val navigation =
                    Intent(this, OnLineImportActivity::class.java)
                        .setData(Uri.parse(checkNotNull(receipt.payload)))
                PreparedAssociationDelivery({
                    startActivity(navigation)
                    finish()
                })
            }
            AssociationNativeKind.OpenBook -> {
                val book =
                    withContext(Dispatchers.IO) {
                        GSON.fromJsonObject<Book>(checkNotNull(receipt.payload)).getOrThrow()
                    }
                PreparedAssociationDelivery({
                    startActivityForBook(book)
                    finish()
                })
            }
            AssociationNativeKind.Finish -> {
                if (receipt.type == "permissionDenied") delay(2_000)
                PreparedAssociationDelivery({
                    if (hostKind == AssociationHostKind.File || receipt.payload == null) {
                        receipt.payload?.let { toastOnUi(it) }
                        finish()
                    }
                })
            }
        }
    }

    private fun recordPermission(
        ticket: String,
        receipt: AssociationNativeReceipt,
        granted: Boolean,
    ) {
        cleanupScope.launch {
            runCatching {
                val accepted =
                    dependencies.nativeResults.record(
                        ticket,
                        AssociationNativeResult(receipt, permissionGranted = granted),
                    )
                if (!accepted) return@runCatching
                if (!isFinishing && !isDestroyed) {
                    if (!granted) toastOnUi("请求存储权限失败。")
                    importModel.reconcileNativeResults()
                }
            }
                .onFailure { if (!isFinishing && !isDestroyed) toastOnUi(it.localizedMessage) }
        }
    }

    private fun showImport(receipt: AssociationNativeReceipt) {
        val source = checkNotNull(receipt.payload)
        when (receipt.type) {
            "bookSource" -> showDialogFragment(ImportBookSourceDialog(source, true))
            "rssSource" -> showDialogFragment(ImportRssSourceDialog(source, true))
            "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(source, true))
            "highlightRule" -> showImportHighlightRuleDialog(source, true)
            "httpTts" -> showDialogFragment(ImportHttpTtsDialog(source, true))
            "theme" -> showDialogFragment(ImportThemeDialog(source, true))
            "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(source, true))
            "dictRule" -> showDialogFragment(ImportDictRuleDialog(source, true))
            "autoTask" -> showDialogFragment(ImportAutoTaskDialog(source, true))
            "addToBookshelf" -> showDialogFragment(AddToBookshelfDialog(source, true))
            "bookshelf",
            "backup" ->
                showDialogFragment(
                    ImportDataDialog.fromSession(checkNotNull(importModel.ownedTicket))
                )
            else -> error("Unsupported import type")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toastOnUi(R.string.importing)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pickerOwner?.let { outState.putString(PICKER_OWNER_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && privateOwnerAccepted) {
            val model = importModel
            cleanupScope.launch { runCatching { model.closeOwnedSession() } }
        }
        super.onDestroy()
    }

    private companion object {
        const val LOCAL_PREVIEW_TAG = "sharedLocalBooks"
        const val PICKER_OWNER_KEY = "association.picker"
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
