package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.help.book.cacheLocalUri
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.download.showChapterDownloadDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.find
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isTv
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

fun Context.showBookDownloadDialog(book: Book) {
    showChapterDownloadDialog(book)
}

/** 阅读界面 */
abstract class BaseReadBookActivity : BaseComposeActivity(imageBg = false) {
    protected val viewModel by viewModels<ReadBookViewModel>()
    abstract val readMenu: ReaderMenuController
    abstract val searchMenu: ReaderSearchControls
    protected var navigationBarVisible by mutableStateOf(false)
        private set

    protected val menuLayoutIsVisible
        get() = bottomDialog > 0 || readMenu.isVisible || searchMenu.bottomMenuVisible

    var bottomDialog = 0
        set(value) {
            if (field != value) {
                field = value
                onBottomDialogChange()
            }
        }

    private val selectBookFolderResult =
        registerForActivityResult(HandleFileContract()) {
            it.uri?.let { uri ->
                ReadBook.book?.let { book ->
                    FileDoc.fromUri(uri, true).find(book.originName)?.let { doc ->
                        AppConfig.importBookPath = uri.toString()
                        LocalBook.withParserCacheInvalidated(book) {
                            book.cacheLocalUri(doc.uri)
                        }
                        viewModel.loadChapterList(book)
                    } ?: ReadBook.upMsg("找不到文件")
                }
            } ?: ReadBook.upMsg("没有权限访问")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReadBook.msg = null
        setOrientation()
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
        supportFragmentManager.setFragmentResultListener(SimulatedReadingDialog.RESULT, this) {
            _,
            result ->
            if (ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode) == result.getString("owner"))
                viewModel.initData(intent)
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        viewModel.permissionDenialLiveData.observe(this) {
            selectBookFolderResult.launch {
                mode = HandleFileContract.DIR_SYS
                title = "选择书籍所在文件夹"
            }
        }
        if (!LocalConfig.readHelpVersionIsLast) {
            if (isTv) {
                showCustomPageKeyConfig()
            } else {
                showClickRegionalConfig()
            }
        }
    }

    private fun onBottomDialogChange() {
        when (bottomDialog) {
            // A dialog can return to the reading menu before it is dismissed.
            0 -> if (menuLayoutIsVisible) onMenuShow() else onMenuHide()
            1 -> onMenuShow()
        }
    }

    open fun onMenuShow() {}

    open fun onMenuHide() {}

    fun showPaddingConfig() {
        showDialogFragment<PaddingConfigDialog>()
    }

    fun showBgTextConfig() {
        showDialogFragment<BgTextConfigDialog>()
    }

    fun showClickRegionalConfig() {
        showDialogFragment<ClickActionConfigDialog>()
    }

    private fun showCustomPageKeyConfig() {
        PageKeyDialog(this).show()
    }

    /** 屏幕方向 */
    @SuppressLint("SourceLockedOrientationActivity")
    fun setOrientation() {
        when (AppConfig.screenOrientation) {
            "0" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "1" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "2" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "3" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "4" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "5" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    /** 更新状态栏,导航栏 */
    fun upSystemUiVisibility(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true,
        useBgMeanColor: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.run {
                if (toolBarHide && ReadBookConfig.hideNavigationBar) {
                    hide(WindowInsets.Type.navigationBars())
                } else {
                    show(WindowInsets.Type.navigationBars())
                }
                if (toolBarHide && ReadBookConfig.hideStatusBar) {
                    hide(WindowInsets.Type.statusBars())
                } else {
                    show(WindowInsets.Type.statusBars())
                }
            }
        }
        upSystemUiVisibilityO(isInMultiWindow, toolBarHide)
        if (toolBarHide) {
            setLightStatusBar(ReadBookConfig.durConfig.curStatusIconDark())
        } else {
            val statusBarColor =
                if (
                    AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0 ||
                        useBgMeanColor
                ) {
                    ReadBookConfig.bgMeanColor
                } else {
                    ThemeStore.statusBarColor(this, AppConfig.isTransparentStatusBar)
                }
            setLightStatusBar(ColorUtils.isColorLight(statusBarColor))
        }
    }

    @Suppress("DEPRECATION")
    private fun upSystemUiVisibilityO(
        isInMultiWindow: Boolean,
        toolBarHide: Boolean = true,
    ) {
        var flag =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        if (!isInMultiWindow) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (ReadBookConfig.hideNavigationBar) {
            flag = flag or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (toolBarHide) {
                flag = flag or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
        if (ReadBookConfig.hideStatusBar && toolBarHide) {
            flag = flag or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        window.decorView.systemUiVisibility = flag
    }

    override fun upNavigationBarColor() {
        upNavigationBar()
        when {
            readMenu.isVisible -> super.upNavigationBarColor()
            searchMenu.bottomMenuVisible -> super.upNavigationBarColor()
            bottomDialog > 0 -> super.upNavigationBarColor()
            !AppConfig.immNavigationBar -> super.upNavigationBarColor()
            else -> setNavigationBarColorAuto(ReadBookConfig.bgMeanColor)
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun upNavigationBar() {
        navigationBarVisible = menuLayoutIsVisible
    }

    /** 保持亮屏 */
    fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 适配刘海 */
    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        if (ReadBookConfig.readBodyToLh) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        }
                }
        }
    }

    fun showDownloadDialog() {
        ReadBook.book?.let { showBookDownloadDialog(it) }
    }

    fun showSimulatedReading() {
        val book = ReadBook.book ?: return
        val request =
            SimulatedReadingRequest(
                book.bookUrl,
                SimulatedReadingSettings(
                    book.getReadSimulating(),
                    book.getStartDate()?.toString().orEmpty(),
                    book.getStartChapter().toString(),
                    book.getDailyChapters().toString(),
                    book.totalChapterNum,
                ),
            )
        val requests = FileSimulatedReadingRequestRepository(applicationContext)
        lifecycleScope.launch {
            var ticket: String? = null
            var shown = false
            try {
                ticket = requests.create(request)
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                if (
                    !isFinishing &&
                        !supportFragmentManager.isStateSaved &&
                        ReadBook.book?.bookUrl == request.bookUrl
                ) {
                    SimulatedReadingDialog.newInstance(ticket)
                        .show(supportFragmentManager, "simulated-reading")
                    shown = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toastOnUi(error.localizedMessage ?: getString(R.string.error))
            } finally {
                if (!shown) ticket?.let { withContext(NonCancellable) { requests.release(it) } }
            }
        }
    }

    fun showCharsetConfig() {
        if (
            supportFragmentManager.findFragmentByTag(ReaderCharsetDialog::class.simpleName) == null
        ) {
            showDialogFragment(ReaderCharsetDialog.create())
        }
    }

    fun showPageAnimConfig(success: () -> Unit) {
        val items = arrayListOf<String>()
        items.add(getString(R.string.btn_default_s))
        items.add(getString(R.string.page_anim_cover))
        items.add(getString(R.string.page_anim_slide))
        items.add(getString(R.string.page_anim_simulation))
        items.add(getString(R.string.page_anim_scroll))
        items.add(getString(R.string.page_anim_none))
        selector(R.string.page_anim, items) { _, i ->
            ReadBook.book?.setPageAnim(i - 1)
            success()
        }
    }

    fun isPrevKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val prevKeysStr = getPrefString(PreferKey.prevKeys)
        return prevKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }

    fun isNextKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false
        }
        val nextKeysStr = getPrefString(PreferKey.nextKeys)
        return nextKeysStr?.split(",")?.contains(keyCode.toString()) ?: false
    }
}
