@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
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
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
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
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.interop.LegacyMainPager
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.navigation.MainDestination
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.clearClip
import io.legado.app.utils.getClipText
import io.legado.app.utils.isCreated
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 主界面 */
@Suppress("PrivatePropertyName")
class MainActivity : BaseComposeActivity(), MainViewModel.CallBack {

    val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idRss = 2
    private val idMy = 3
    private var updatingNavigation = false
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private val pagePosition
        get() = viewModel.uiState.value.selectedIndex

    private val fragmentMap = hashMapOf<Int, Fragment>()
    private val bottomMenuCount
        get() = viewModel.uiState.value.destinations.size

    private val EXIT_INTERVAL = 2000L
    private val realPositions
        get() = viewModel.uiState.value.destinations.map { it.legacyId }

    private val viewPagerMain by lazy { ViewPager(this).apply { id = R.id.view_pager_main } }
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private var passphraseReadGeneration = 0

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        MainRoute(viewModel, onDestinationReselected = ::onDestinationReselected) { state ->
            LegacyMainPager(viewPagerMain, state.selectedIndex)
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upBottomBarSkin()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                viewModel.selectDestination(MainDestination.Bookshelf)
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
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
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }
            MainDestination.Explore -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[idExplore] as? ExploreFragment)?.compressExplore()
                }
            }
            else -> Unit
        }
    }

    private fun initView() {
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = 3
        viewPagerMain.adapter = adapter
        viewPagerMain.setCurrentItem(pagePosition, false)
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
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
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
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
    }

    private fun upBottomMenu() {
        updatingNavigation = true
        try {
            viewModel.refreshNavigation()
            adapter.notifyDataSetChanged()
            if (viewPagerMain.adapter != null) viewPagerMain.setCurrentItem(pagePosition, false)
        } finally {
            updatingNavigation = false
        }
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

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            if (updatingNavigation) return
            viewModel.uiState.value.destinations
                .getOrNull(position)
                ?.let(viewModel::selectDestination)
        }
    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position ?: return POSITION_NONE
            if (position !in 0 until bottomMenuCount) return POSITION_NONE
            val fragmentId = getId(position)
            if (
                (fragmentId == idBookshelf1 && any is BookshelfFragment1) ||
                    (fragmentId == idBookshelf2 && any is BookshelfFragment2) ||
                    (fragmentId == idExplore && any is ExploreFragment) ||
                    (fragmentId == idRss && any is RssFragment) ||
                    (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idRss -> RssFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }
    }

    override fun openImportUi(type: Int, source: String) {
        when (type) {
            0 -> showDialogFragment(ImportBookSourceDialog(source))
            1 -> showDialogFragment(ImportRssSourceDialog(source))
            2 -> showDialogFragment(ImportReplaceRuleDialog(source))
        }
    }
}
