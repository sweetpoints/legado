package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.EventBus
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.utils.postEvent

internal class AppThemeSettingsPlatform(context: Context) : ThemeSettingsPlatform {
    private val application = context.applicationContext

    override suspend fun apply(night: Boolean?) {
        if (night == null) ThemeConfig.applyDayNightAsync(application)
        else if (AppConfig.isNightTheme == night) {
            ThemeConfig.applyTheme(application)
            recreate()
        }
    }

    override fun recreate() {
        postEvent(EventBus.RECREATE, "")
    }

    override fun launcher(value: String) {
        LauncherIconHelp.changeIcon(value)
    }

    override fun follow(enabled: Boolean, auto: Boolean) =
        WallpaperTheme.setFollow(application, enabled, auto)

    override fun manualColor() {
        WallpaperTheme.onColorPreferenceChanged(application)
    }
}
