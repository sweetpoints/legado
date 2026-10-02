package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.windowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class WelcomeActivity : BaseComposeActivity() {

    private val welcomeUiState by lazy {
        val dark = ThemeConfig.getTheme() == Theme.Dark
        WelcomeUiState(
            showText = if (dark) AppConfig.welcomeShowTextDark else AppConfig.welcomeShowText,
            showIcon = if (dark) AppConfig.welcomeShowIconDark else AppConfig.welcomeShowIcon,
        )
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        WelcomeScreen(welcomeUiState)
    }
    private var startMainJob: Job? = null

    private val broughtToFront: Boolean
        get() = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0

    override fun shouldShowWindowBackground(): Boolean =
        getPrefInt(PreferKey.welcomeShowTime, 500) != 0 && !broughtToFront

    override fun shouldCreateContentView(): Boolean {
        if (broughtToFront || getPrefInt(PreferKey.welcomeShowTime, 500) == 0) {
            if (broughtToFront) finish() else startMainActivity()
            return false
        }
        return true
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (broughtToFront) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 500)
            if (welcomeShowTime == 0) {
                startMainActivity()
            } else {
                startMainJob = lifecycleScope.launch {
                    delay(welcomeShowTime.toLong())
                    startMainActivity()
                }
            }
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun finish() {
        startMainJob?.cancel()
        startMainJob = null
        super.finish()
    }

    override fun upBackgroundImage() {
        if (!getPrefBoolean(PreferKey.customWelcome)) {
            super.upBackgroundImage()
            return
        }
        val key = if (ThemeConfig.getTheme() == Theme.Dark) PreferKey.welcomeImageDark else PreferKey.welcomeImage
        val path = getPrefString(key) ?: return
        val size = windowManager.windowSize
        lifecycleScope.launch {
            val drawable = withContext(Dispatchers.IO) {
                runCatching {
                    if (path.endsWith(".9.png")) {
                        BitmapUtils.decodeNinePatchDrawable(path)
                    } else {
                        BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels)?.toDrawable(resources)
                    }
                }.getOrNull()
            }
            if (drawable != null && !isFinishing && !isDestroyed) window.decorView.background = drawable
        }
    }

    private fun startMainActivity() {
        startMainJob = lifecycleScope.launch {
            val openReader = getPrefBoolean(PreferKey.defaultToRead) && withContext(Dispatchers.IO) {
                appDb.bookDao.lastReadBook != null
            }
            startActivity<MainActivity>()
            if (openReader) startActivity<ReadBookActivity>()
            finish()
        }
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
