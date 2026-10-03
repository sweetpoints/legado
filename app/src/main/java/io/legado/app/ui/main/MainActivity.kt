@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.preferences.BookshelfSettingsEffects
import io.legado.app.data.preferences.dispatchEvents
import io.legado.app.data.repository.AppMainRssRepository
import io.legado.app.data.repository.FileMainRssSessionRepository
import io.legado.app.data.repository.MainRssDestination
import io.legado.app.data.repository.MainRssPrepared
import io.legado.app.data.repository.RoomBookshelfFolderRepository
import io.legado.app.data.repository.RoomBookshelfHomeRepository
import io.legado.app.data.repository.RoomBookshelfPageRepository
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.SourceSharePassphraseImportPolicy
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.isIgnoredAppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfViewModel
import io.legado.app.ui.main.bookshelf.MainBookshelfHost
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputDialog
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputResult
import io.legado.app.ui.main.bookshelf.settings.BookshelfSettingsDialog
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style1.BookshelfHomeGroup
import io.legado.app.ui.main.bookshelf.style1.BookshelfHomeViewModel
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageParameters
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageViewModel
import io.legado.app.ui.main.bookshelf.style2.BookshelfFolderViewModel
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.AppExploreHomeRepository
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.explore.ExploreHomePrepared
import io.legado.app.ui.main.explore.ExploreHomeViewModel
import io.legado.app.ui.main.explore.FileExploreHomeSessionStorage
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.my.MyViewModel
import io.legado.app.ui.main.my.openMyItem as openMyNavigationItem
import io.legado.app.ui.main.my.showMyServiceActions as showMyNavigationServiceActions
import io.legado.app.ui.main.rss.MainRssAction
import io.legado.app.ui.main.rss.MainRssViewModel
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.clearClip
import io.legado.app.utils.getClipText
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 主界面 */
@Suppress("PrivatePropertyName")
class MainActivity : BaseComposeActivity(), MainViewModel.CallBack, MainBookshelfHost {

    val viewModel by viewModels<MainViewModel>()
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private val exitInterval = 2000L
    private val bookshelfRepository by lazy { RoomBookshelfHomeRepository(applicationContext) }
    private val folderRepository by lazy { RoomBookshelfFolderRepository(applicationContext) }
    private var legacyExploreSessionToken: String? = null
    private var legacyRssSessionId: String? = null
    private val mutableHostMigration = MutableStateFlow(MainHostMigrationState())
    internal val hostMigration = mutableHostMigration.asStateFlow()
    private val mutableLegacyTransferStates =
        MutableStateFlow<List<LegacyBookshelfTransferState>>(emptyList())
    internal val legacyTransferStates = mutableLegacyTransferStates.asStateFlow()
    private val legacyTransferOwners = mutableMapOf<String, BaseBookshelfFragment>()
    private val legacyTransferCollectors = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var legacyMigrationRunning = false
    internal val uiStateBookshelfStyle: Int
        get() = viewModel.uiState.value.bookshelfStyle

    override val bookshelfTransferModel by viewModels<BookshelfViewModel>()
    internal val bookshelfHomeModel by
        viewModels<BookshelfHomeViewModel> {
            viewModelFactory {
                initializer {
                    BookshelfHomeViewModel(bookshelfRepository, createSavedStateHandle())
                }
            }
        }
    internal val bookshelfFolderModel by
        viewModels<BookshelfFolderViewModel> {
            viewModelFactory {
                initializer { BookshelfFolderViewModel(folderRepository, createSavedStateHandle()) }
            }
        }
    internal val exploreHomeModel by
        viewModels<ExploreHomeViewModel> {
            viewModelFactory {
                initializer {
                    val saved = createSavedStateHandle()
                    val token =
                        legacyExploreSessionToken?.also { saved["exploreHome.session"] = it }
                            ?: ExploreHomeViewModel.token(saved)
                    ExploreHomeViewModel(
                        AppExploreHomeRepository(),
                        FileExploreHomeSessionStorage(applicationContext, token),
                        sessionToken = token,
                    )
                }
            }
        }
    internal val mainRssModel by
        viewModels<MainRssViewModel> {
            viewModelFactory {
                initializer {
                    MainRssViewModel(
                        AppMainRssRepository(),
                        FileMainRssSessionRepository(),
                        createSavedStateHandle().apply {
                            if (legacyRssSessionId != null) {
                                keys()
                                    .filter { it.startsWith("mainRss.") }
                                    .forEach { remove<Any?>(it) }
                                this["mainRss.session"] = legacyRssSessionId
                            }
                        },
                    )
                }
            }
        }
    internal val myViewModel by viewModels<MyViewModel>()
    private val bookshelfPageModels = mutableMapOf<Long, BookshelfPageViewModel>()
    private val importBookshelf =
        registerForActivityResult(HandleFileContract()) { result ->
            val transfer = bookshelfTransferModel.transfer
            val requestId =
                activityImportRequestId
                    ?: transfer.launchedImportRequestId
                    ?: return@registerForActivityResult
            activityImportRequestId = null
            val groupId = transfer.importReturned(requestId) ?: return@registerForActivityResult
            result.uri?.let { bookshelfTransferModel.importBookshelfFile(it.toString(), groupId) }
        }
    private val exportBookshelf =
        registerForActivityResult(HandleFileContract()) { result ->
            val transfer = bookshelfTransferModel.transfer
            val requestId =
                activityExportRequestId
                    ?: transfer.launchedExportRequestId
                    ?: return@registerForActivityResult
            activityExportRequestId = null
            val path = transfer.launchedExportPath
            if (requestId != null && path != null) transfer.exportReturned(path, requestId)
            result.uri?.let { uri ->
                showDialogFragment(
                    BookshelfInputDialog.create(
                        2,
                        value = uri.toString(),
                        summary =
                            if (uri.toString().isAbsUrl()) DirectLinkUpload.getSummary() else "",
                    )
                )
            }
        }
    private var activityImportRequestId: String? = null
    private var activityExportRequestId: String? = null
    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private var passphraseReadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = MainRestoreFragmentFactory()
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        MainRoute(viewModel, onDestinationReselected = ::onDestinationReselected) { state ->
            MainDestinationPager(state, viewModel::selectDestination) { destination, _ ->
                MainDestinationHost(this@MainActivity, destination)
            }
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        migrateRestoredDestinations()
        upBottomMenu()
        upBottomBarSkin()
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.uiState.value.selectedDestination != MainDestination.Bookshelf) {
                viewModel.selectDestination(MainDestination.Bookshelf)
                return@addCallback
            }
            if (uiStateBookshelfStyle == 1 && bookshelfFolderModel.back()) {
                return@addCallback
            }
            if (System.currentTimeMillis() - exitTime > exitInterval) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    private fun migrateRestoredDestinations() {
        if (legacyMigrationRunning || mutableHostMigration.value.ready) return
        legacyMigrationRunning = true
        lifecycleScope.launch {
            try {
                val restored = supportFragmentManager.fragments.toList()
                val explore = restored.filterIsInstance<ExploreFragment>().firstOrNull()
                val rss = restored.filterIsInstance<RssFragment>().firstOrNull()
                val my = restored.filterIsInstance<MyFragment>().firstOrNull()
                val bookshelves = restored.filterIsInstance<BaseBookshelfFragment>()
                val legacyGroupSelection =
                    restored
                        .filterIsInstance<BookshelfFragment1>()
                        .firstOrNull()
                        ?.captureHostSelection()
                val legacyFolderNavigation =
                    restored
                        .filterIsInstance<BookshelfFragment2>()
                        .firstOrNull()
                        ?.captureHostNavigation()

                legacyExploreSessionToken = explore?.homeModel?.sessionToken
                legacyRssSessionId = rss?.homeModel?.sessionId
                val myDraft = my?.captureCustomizationDraftForHostMigration()

                explore?.homeModel?.prepareForHostMigration()
                rss?.homeModel?.prepareForHostMigration()

                if (mutableHostMigration.value.error != null) {
                    exploreHomeModel.retry()
                    mainRssModel.retry()
                }
                myViewModel.seedCustomizationDraftFromLegacy(myDraft)
                if (uiStateBookshelfStyle == 1) {
                    bookshelfFolderModel.seedHostNavigation(legacyFolderNavigation)
                } else {
                    bookshelfHomeModel.seedHostSelection(legacyGroupSelection)
                }
                exploreHomeModel.awaitHostRestore()
                mainRssModel.bind()
                mainRssModel.awaitHostRestore()

                val retainedTransferOwners =
                    bookshelves
                        .filter { owner ->
                            val transfer = owner.viewModel.transfer
                            val hasPicker =
                                transfer.launchedImportRequestId != null ||
                                    transfer.exportPickerInFlight ||
                                    transfer.pendingExport.value != null
                            val hasWork = owner.viewModel.operations.value.isNotEmpty()
                            val needsImportRecovery = transfer.pendingFileImport.value != null
                            hasPicker || hasWork || needsImportRecovery
                        }
                        .toSet()
                retainedTransferOwners.forEach { owner ->
                    if (
                        owner.viewModel.transfer.launchedImportRequestId != null ||
                            owner.viewModel.transfer.exportPickerInFlight
                    ) {
                        owner.retainForPendingResult()
                    } else {
                        owner.retainForPendingTransfer()
                    }
                    observeLegacyTransferOwner(owner)
                }
                supportFragmentManager.commitNow {
                    restored
                        .filter {
                            it is ExploreFragment ||
                                it is RssFragment ||
                                it is MyFragment ||
                                it is BaseBookshelfFragment
                        }
                        .filterNot { it in retainedTransferOwners }
                        .forEach(::remove)
                }
                mutableHostMigration.value = MainHostMigrationState(ready = true)
            } catch (failure: Exception) {
                mutableHostMigration.value =
                    MainHostMigrationState(error = failure.localizedMessage ?: "主界面状态恢复失败")
            } finally {
                legacyMigrationRunning = false
            }
        }
    }

    internal fun retryHostMigration() = migrateRestoredDestinations()

    private fun observeLegacyTransferOwner(owner: BaseBookshelfFragment) {
        val ownerId = owner.tag ?: "legacy-${System.identityHashCode(owner)}"
        legacyTransferOwners[ownerId] = owner
        if (legacyTransferCollectors.containsKey(ownerId)) return
        val transfer = owner.viewModel.transfer
        legacyTransferCollectors[ownerId] = lifecycleScope.launch {
            combine(
                    owner.viewModel.operations,
                    transfer.addProgress,
                    transfer.pendingFileImport,
                    transfer.pendingExport,
                ) { operations, progress, fileImport, exportPath ->
                    val pendingPicker =
                        transfer.launchedImportRequestId != null || transfer.exportPickerInFlight
                    LegacyBookshelfTransferState(
                        ownerId = ownerId,
                        operationLabel = operations.firstOrNull()?.label,
                        progress = progress.takeIf { it >= 0 },
                        needsFileImportRecovery = fileImport != null && operations.isEmpty(),
                    ) to
                        (pendingPicker ||
                            operations.isNotEmpty() ||
                            fileImport != null ||
                            exportPath != null)
                }
                .collect { (state, keepOwner) ->
                    mutableLegacyTransferStates.value =
                        mutableLegacyTransferStates.value
                            .filterNot { it.ownerId == ownerId }
                            .let { states -> if (keepOwner) states + state else states }
                    val exportPath = transfer.pendingExport.value
                    if (
                        exportPath != null &&
                            !transfer.exportPickerInFlight &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !supportFragmentManager.isStateSaved
                    ) {
                        owner.launchPendingExportPicker(exportPath)
                    }
                    if (!keepOwner && !owner.pendingResultBridge) removeLegacyTransferOwner(ownerId)
                }
        }
    }

    private fun removeLegacyTransferOwner(ownerId: String) {
        if (supportFragmentManager.isStateSaved) return
        val owner = legacyTransferOwners[ownerId] ?: return
        if (owner.isAdded && !owner.isRemoving) supportFragmentManager.commit { remove(owner) }
        legacyTransferOwners.remove(ownerId)
        legacyTransferCollectors.remove(ownerId)?.cancel()
    }

    internal fun retryLegacyFileImport(ownerId: String) {
        if (!destinationReady(MainDestination.Bookshelf)) return
        legacyTransferOwners[ownerId]?.viewModel?.retryPendingFileImport()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            // 隐私协议
            if (!privacyPolicy()) return@launch
            // 版本更新
            upVersion()
            // 设置本地密码
            setLocalPassword()
            notifyAppCrash()
            // 备份同步
            backupSync()
            // 设置回调
            viewModel.setActivityCallback(this@MainActivity)
            // 自动更新书源
            window.decorView.postDelayed(1000) {
                viewModel.ruleSubsUp()
            }
            scheduleSourceSharePassphraseRead(1500)
            // 自动更新书籍
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                window.decorView.postDelayed(2000) {
                    viewModel.upAllBookToc()
                }
            }
            window.decorView.postDelayed(3000) {
                viewModel.postLoad()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        legacyTransferOwners.keys.toList().forEach { ownerId ->
            val owner = legacyTransferOwners[ownerId] ?: return@forEach
            val transfer = owner.viewModel.transfer
            val keepOwner =
                owner.pendingResultBridge ||
                    owner.viewModel.operations.value.isNotEmpty() ||
                    transfer.pendingFileImport.value != null ||
                    transfer.pendingExport.value != null
            if (!keepOwner) removeLegacyTransferOwner(ownerId)
            else {
                transfer.pendingExport.value
                    ?.takeIf { !transfer.exportPickerInFlight }
                    ?.takeIf { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    ?.let(owner::launchPendingExportPicker)
            }
        }
        if (
            SourceSharePassphraseImportPolicy.shouldScheduleOnResume(
                privacyPolicyOk = LocalConfig.privacyPolicyOk
            )
        ) {
            scheduleSourceSharePassphraseRead(500)
        }
    }

    override fun onPause() {
        super.onPause()
        pendingPassphraseRead = false
        passphraseReadGeneration++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingPassphraseRead && canAwaitPassphraseWindowFocus()) {
            readSourceSharePassphrase(200)
        } else if (hasFocus) {
            pendingPassphraseRead = false
        }
    }

    private fun onDestinationReselected(destination: MainDestination) {
        when (destination) {
            MainDestination.Bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    if (uiStateBookshelfStyle == 1) bookshelfFolderModel.gotoTop()
                    else bookshelfPageModelForSelectedGroup()?.gotoTop()
                }
            }
            MainDestination.Explore -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    exploreHomeModel.compressExplore()
                }
            }
            else -> Unit
        }
    }

    /** 用户隐私与协议 */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resume(false)
            }
        }
    }

    /** 版本更新日志 */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (
                    LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds <
                        System.currentTimeMillis()
                ) {
                    AppUpdate.gitHubUpdate.check(lifecycleScope).onSuccess {
                        if (isIgnoredAppUpdate(it.tagName, LocalConfig.ignoreUpdateVersion)) {
                            return@onSuccess
                        }
                        if (supportFragmentManager.isStateSaved) return@onSuccess
                        showDialogFragment(UpdateDialog(it))
                    }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog =
                TextDialog(
                    getString(R.string.help),
                    help,
                    TextDialog.Mode.MD,
                    showToc = true,
                )
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else {
            block.resume(null)
        }
    }

    /** 设置本地密码 */
    private suspend fun setLocalPassword() {
        if (withContext(IO) { LocalConfig.password != null }) return
        val chosen =
            suspendCancellableCoroutine<String?> { continuation ->
                val dialog = ComponentDialog(this)
                var choice: String? = null
                fun complete(value: String?) {
                    choice = value
                    dialog.dismiss()
                }
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setContentView(
                    ComposeView(this).apply {
                        setViewCompositionStrategy(
                            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                        )
                        setContent {
                            LegadoComposeTheme {
                                MainLocalPasswordScreen(
                                    onConfirm = { complete(it) },
                                    onSkip = { complete("") },
                                    onDismiss = { complete(null) },
                                )
                            }
                        }
                    }
                )
                dialog.setOnDismissListener {
                    if (continuation.isActive) continuation.resume(choice)
                }
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawableResource(R.color.transparent)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        if (chosen != null) withContext(IO + NonCancellable) { LocalConfig.password = chosen }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /** 备份同步 */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton {
                        LocalConfig.lastBackup =
                            maxOf(
                                LocalConfig.lastBackup,
                                lastBackupFile.lastModify,
                            )
                    }
                    okButton {
                        viewModel.restoreWebDav(
                            lastBackupFile.displayName,
                            lastBackupFile.lastModify,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        viewModel.setActivityCallback(null)
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /** 如果重启太快fragment不会重建,这里更新一下书架的排序 */
    override fun recreate() {
        bookshelfHomeModel.refresh()
        bookshelfFolderModel.refresh()
        super.recreate()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            upBottomMenu()
            upBottomBarSkin()
            if (it) viewModel.selectDestination(MainDestination.My)
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_SKIN) {
            upBottomBarSkin()
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
        observeEvent<String>(EventBus.UP_BOOKSHELF) { key ->
            onBookshelfBookUpdated(key)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            refreshBookshelfBookUpdates()
        }
        observeEventSticky<String>(EventBus.WEB_SERVICE, EventBus.MCP_SERVICE) {
            myViewModel.refreshRuntimeState()
        }
    }

    private fun upBottomMenu() {
        viewModel.refreshNavigation()
    }

    private fun scheduleSourceSharePassphraseRead(delayMillis: Long) {
        passphraseReadGeneration++
        pendingPassphraseRead = true
        if (hasWindowFocus()) {
            readSourceSharePassphrase(delayMillis, passphraseReadGeneration)
        }
    }

    private fun canAwaitPassphraseWindowFocus(): Boolean {
        return SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
            privacyPolicyOk = LocalConfig.privacyPolicyOk,
            isFinishing = isFinishing,
            isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isFragmentStateSaved = supportFragmentManager.isStateSaved,
        )
    }

    private fun readSourceSharePassphrase(
        delayMillis: Long,
        generation: Int = passphraseReadGeneration,
    ) {
        pendingPassphraseRead = false
        window.decorView.postDelayed(delayMillis) {
            if (generation != passphraseReadGeneration) return@postDelayed
            val hasWindowFocus = hasWindowFocus()
            if (!hasWindowFocus) {
                pendingPassphraseRead = canAwaitPassphraseWindowFocus()
            }
            if (
                !SourceSharePassphraseImportPolicy.canReadClipboard(
                    privacyPolicyOk = LocalConfig.privacyPolicyOk,
                    isFinishing = isFinishing,
                    isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    isFragmentStateSaved = supportFragmentManager.isStateSaved,
                    hasWindowFocus = hasWindowFocus,
                )
            ) {
                return@postDelayed
            }
            val text = getClipText()?.takeIf { it.isNotBlank() } ?: return@postDelayed
            if (text == lastPassphraseText) return@postDelayed
            when (val result = SourceSharePassphrase.decode(text)) {
                SourceSharePassphrase.DecodeResult.NotFound -> {
                    lastPassphraseText = null
                }

                SourceSharePassphrase.DecodeResult.Invalid -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_invalid)
                }

                SourceSharePassphrase.DecodeResult.Expired -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_expired)
                }

                is SourceSharePassphrase.DecodeResult.Success -> {
                    lastPassphraseText = text
                    clearClip()
                    val value = result.value
                    when (value.type) {
                        SourceSharePassphrase.Type.BOOK_SOURCE ->
                            showDialogFragment(ImportBookSourceDialog(value.url))

                        SourceSharePassphrase.Type.RSS_SOURCE ->
                            showDialogFragment(ImportRssSourceDialog(value.url))

                        SourceSharePassphrase.Type.DICT_RULE ->
                            showDialogFragment(ImportDictRuleDialog(value.url))

                        SourceSharePassphrase.Type.REPLACE_RULE ->
                            showDialogFragment(ImportReplaceRuleDialog(value.url))

                        SourceSharePassphrase.Type.TOC_RULE ->
                            showDialogFragment(ImportTxtTocRuleDialog(value.url))

                        SourceSharePassphrase.Type.TTS_RULE ->
                            showDialogFragment(ImportHttpTtsDialog(value.url))

                        SourceSharePassphrase.Type.AUTO_TASK ->
                            showDialogFragment(ImportAutoTaskDialog(value.url))
                    }
                }
            }
        }
    }

    private fun upBottomBarSkin() {
        viewModel.refreshBottomBarSkin()
    }

    internal fun bookshelfPageModel(group: BookshelfHomeGroup, index: Int): BookshelfPageViewModel =
        bookshelfPageModels.getOrPut(group.id) {
            ViewModelProvider(
                this,
                viewModelFactory {
                    initializer {
                        BookshelfPageViewModel(
                                RoomBookshelfPageRepository(applicationContext),
                                createSavedStateHandle(),
                            )
                            .also {
                                it.configure(
                                    BookshelfPageParameters(
                                        index,
                                        group.id,
                                        group.sort,
                                        group.refresh,
                                        group.onlyRead,
                                    )
                                )
                            }
                    }
                },
            )["bookshelf.page.${group.id}", BookshelfPageViewModel::class.java]
        }

    private fun bookshelfPageModelForSelectedGroup(): BookshelfPageViewModel? =
        bookshelfHomeModel.state.value.selectedGroup?.let { group ->
            bookshelfPageModel(group, bookshelfHomeModel.state.value.selectedIndex)
        }

    internal fun destinationReady(destination: MainDestination): Boolean =
        !isFinishing &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved &&
            viewModel.uiState.value.selectedDestination == destination

    internal fun openExplorePrepared(prepared: ExploreHomePrepared) {
        if (!destinationReady(MainDestination.Explore)) return
        val effect = prepared.effect
        when (effect.action) {
            "manage" -> startActivity<BookSourceActivity>()
            "edit" ->
                startActivity<BookSourceEditActivity> { putExtra("sourceUrl", effect.sourceUrl) }
            "login" ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", effect.sourceUrl)
                }
            "search" -> prepared.searchSource?.let { SearchActivity.start(this, it) }
            "open" ->
                ExploreShowActivity.startPrepared(this, requireNotNull(prepared.resultsSessionId))
            "script" -> exploreHomeModel.execute(effect, this)
        }
    }

    internal fun openRssPrepared(request: MainRssPrepared, readerTicket: String?) {
        if (!destinationReady(MainDestination.Rss)) return
        when (MainRssAction.valueOf(request.action)) {
            MainRssAction.Open ->
                request.navigation?.let { navigation ->
                    when (navigation.destination) {
                        MainRssDestination.Categories ->
                            startActivity<RssSortActivity> {
                                putExtra("sourceUrl", navigation.sourceUrl)
                            }
                        MainRssDestination.ReaderLink,
                        MainRssDestination.ReaderHtml ->
                            ReadRssActivity.startPrepared(this, requireNotNull(readerTicket))
                        MainRssDestination.External -> navigation.value?.let { openUrl(it) }
                    }
                }
            MainRssAction.Edit ->
                request.sourceUrl?.let { url ->
                    startActivity<RssSourceEditActivity> { putExtra("sourceUrl", url) }
                }
            MainRssAction.Login ->
                request.sourceUrl?.let { url ->
                    startActivity<SourceLoginActivity> {
                        putExtra("type", "rssSource")
                        putExtra("key", url)
                    }
                }
            MainRssAction.Subscriptions -> startActivity<RuleSubActivity>()
            MainRssAction.History -> showDialogFragment<ReadRecordDialog>()
            MainRssAction.Favorites -> startActivity<RssFavoritesActivity>()
            MainRssAction.Settings -> startActivity<RssSourceActivity>()
        }
    }

    internal fun editBookshelfGroup(groupId: Long) {
        lifecycleScope.launch {
            bookshelfRepository.group(groupId)?.let { showDialogFragment(GroupEditDialog(it)) }
        }
    }

    internal fun reselectBookshelfGroup(groupId: Long) {
        val group = bookshelfHomeModel.state.value.groups.find { it.id == groupId } ?: return
        val count = bookshelfPageModels[groupId]?.state?.value?.entries?.size ?: 0
        toastOnUi("${group.name}($count)")
    }

    internal fun openBookshelfBook(key: String) {
        val book =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.getBook(key)
            else bookshelfPageModelForSelectedGroup()?.getBook(key)
        book?.let { startActivityForBook(it) }
    }

    internal fun showBookshelfBookInfo(key: String) {
        val book =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.getBook(key)
            else bookshelfPageModelForSelectedGroup()?.getBook(key)
        book?.let {
            startActivity<BookInfoActivity> {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    internal fun refreshBookshelf() {
        if (uiStateBookshelfStyle == 1) {
            if (bookshelfFolderModel.state.value.canRefresh) {
                viewModel.upToc(
                    bookshelfFolderModel.getBooks(),
                    bookshelfFolderModel.state.value.onlyRead,
                )
            }
        } else {
            val model = bookshelfPageModelForSelectedGroup() ?: return
            if (model.state.value.canRefresh) {
                viewModel.upToc(model.getBooks(), model.state.value.parameters.onlyUpdateRead)
            }
        }
    }

    internal fun onBookshelfBookUpdated(key: String) {
        val running = viewModel.isUpdate(key)
        bookshelfPageModels.values.forEach { model ->
            model.setUpdating(key, running)
            model.refreshTimeLabels()
        }
        bookshelfFolderModel.setUpdating(key, running)
        bookshelfFolderModel.refreshTimes()
    }

    internal fun refreshBookshelfBookUpdates() {
        bookshelfPageModels.values.forEach { model ->
            model.replaceUpdating(
                model
                    .getBooks()
                    .filter { viewModel.isUpdate(it.bookUrl) }
                    .map { it.bookUrl }
                    .toSet()
            )
            model.refreshTimeLabels()
        }
        bookshelfFolderModel.replaceUpdating(
            bookshelfFolderModel
                .getBooks()
                .filter { viewModel.isUpdate(it.bookUrl) }
                .map { it.bookUrl }
                .toSet()
        )
        bookshelfFolderModel.refreshTimes()
    }

    internal fun openRecentBook() = openRecentBook(info = false)

    internal fun showRecentBookInfo() = openRecentBook(info = true)

    private fun openRecentBook(info: Boolean) {
        val recent =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.state.value.header.recent
            else bookshelfHomeModel.state.value.header.recent
        val key = recent?.key ?: return
        lifecycleScope.launch {
            bookshelfRepository.book(key)?.let { book ->
                if (info) {
                    startActivity<BookInfoActivity> {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                } else startActivityForBook(book)
            }
        }
    }

    internal fun handleBookshelfMenu(itemId: Int) {
        val groupId =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.state.value.groupId
            else bookshelfHomeModel.state.value.selectedGroup?.id ?: BookGroup.IdAll
        val books =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.getBooks()
            else bookshelfPageModelForSelectedGroup()?.getBooks().orEmpty()
        val onlyUpdateRead =
            if (uiStateBookshelfStyle == 1) bookshelfFolderModel.state.value.onlyRead
            else bookshelfHomeModel.state.value.selectedGroup?.onlyRead ?: false
        when (itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> viewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> showDialogFragment<BookshelfSettingsDialog>()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showDialogFragment(BookshelfInputDialog.create(0, groupId))
            R.id.menu_bookshelf_manage ->
                startActivity<BookshelfManageActivity> { putExtra("groupId", groupId) }
            R.id.menu_download -> startActivity<CacheActivity> { putExtra("groupId", groupId) }
            R.id.menu_export_bookshelf -> bookshelfTransferModel.exportBookshelf(books)
            R.id.menu_import_bookshelf ->
                showDialogFragment(BookshelfInputDialog.create(1, groupId))
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    override fun submitShelfInput(kind: Int, result: BookshelfInputResult) {
        when (kind) {
            0 -> bookshelfTransferModel.addBookByUrl(result.text, result.groupId)
            1 -> bookshelfTransferModel.importBookshelf(result.text, result.groupId)
        }
    }

    override fun selectBookshelfImportFile(groupId: Long) {
        activityImportRequestId = bookshelfTransferModel.transfer.importRequested(groupId)
        bookshelfTransferModel.transfer.importLaunched(requireNotNull(activityImportRequestId))
        importBookshelf.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    override fun applySettingsEffects(effects: BookshelfSettingsEffects) {
        if (effects.updateWaitCount) viewModel.postUpBooksLiveData(true)
        if (effects.updateSort) {
            bookshelfHomeModel.refresh()
            bookshelfFolderModel.refresh()
        }
        effects.changedLayout?.let { layout ->
            if (layout < 2) viewModel.booksGridRecycledViewPool.clear()
            else viewModel.booksListRecycledViewPool.clear()
        }
        effects.dispatchEvents()
    }

    internal fun launchExportBookshelf(path: String) {
        val transfer = bookshelfTransferModel.transfer
        val requestId = transfer.pendingExportRequestId ?: return
        if (transfer.exportPickerInFlight) return
        val file = java.io.File(path)
        if (!file.exists()) {
            toastOnUi(R.string.error)
            transfer.exportReturned(path, requestId)
            return
        }
        activityExportRequestId = requestId
        transfer.exportLaunched(path, requestId)
        exportBookshelf.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData("bookshelf.json", file, "application/json")
        }
    }

    override fun acceptLegacyImportResult(
        owner: BaseBookshelfFragment,
        requestId: String,
        uri: String?,
        groupId: Long?,
    ) {
        if (uri != null && groupId != null) {
            bookshelfTransferModel.importBookshelfFile(uri, groupId)
        }
        finishLegacyResultBridge(owner)
    }

    override fun acceptLegacyExportResult(
        owner: BaseBookshelfFragment,
        requestId: String,
        path: String?,
        uri: String?,
    ) {
        if (uri != null) {
            showDialogFragment(
                BookshelfInputDialog.create(
                    2,
                    value = uri,
                    summary = if (uri.isAbsUrl()) DirectLinkUpload.getSummary() else "",
                )
            )
        }
        finishLegacyResultBridge(owner)
    }

    override fun finishLegacyResultBridge(owner: BaseBookshelfFragment) {
        owner.finishPendingResultBridge()
        val ownerId = legacyTransferOwners.entries.firstOrNull { it.value === owner }?.key
        if (ownerId != null) {
            val stillBusy =
                owner.viewModel.operations.value.isNotEmpty() ||
                    owner.viewModel.transfer.pendingFileImport.value != null ||
                    owner.viewModel.transfer.pendingExport.value != null
            if (!stillBusy) removeLegacyTransferOwner(ownerId)
        } else if (!supportFragmentManager.isStateSaved && !owner.isRemoving) {
            supportFragmentManager.commit { remove(owner) }
        }
    }

    internal fun openMyItem(key: String) = openMyNavigationItem(key)

    internal fun showMyServiceActions(key: String) = showMyNavigationServiceActions(key)

    internal fun showMyHelp() = showHelp("appHelp")

    override fun openImportUi(type: Int, source: String) {
        when (type) {
            0 -> showDialogFragment(ImportBookSourceDialog(source))
            1 -> showDialogFragment(ImportRssSourceDialog(source))
            2 -> showDialogFragment(ImportReplaceRuleDialog(source))
        }
    }
}

/** Restored pager fragments provide state/result ownership only; Compose draws every page. */
private class MainRestoreFragmentFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        super.instantiate(classLoader, className).also { fragment ->
            when (fragment) {
                is ExploreFragment -> fragment.restoreWithoutPageView = true
                is RssFragment -> fragment.restoreWithoutPageView = true
                is MyFragment -> fragment.restoreWithoutPageView = true
                is BaseBookshelfFragment -> fragment.restoreWithoutPageView = true
            }
        }
}
