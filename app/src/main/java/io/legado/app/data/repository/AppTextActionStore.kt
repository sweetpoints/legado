package io.legado.app.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import io.legado.app.R
import io.legado.app.data.preferences.loadTextSelectMenuConfig
import io.legado.app.help.config.AppConfig

/**
 * PackageManager/Intent objects are converted to immutable values before leaving data discovery.
 */
internal class AppTextActionStore(context: Context) : TextActionStore {
    private val context = context.applicationContext

    override suspend fun config() = loadTextSelectMenuConfig(context)

    override suspend fun titles() =
        TextActionKind.entries
            .filterNot { it == TextActionKind.ProcessText }
            .associateWith { kind ->
                context.getString(
                    when (kind) {
                        TextActionKind.Replace -> R.string.replace
                        TextActionKind.Copy -> android.R.string.copy
                        TextActionKind.Bookmark -> R.string.bookmark
                        TextActionKind.Highlight -> R.string.highlight
                        TextActionKind.Aloud -> R.string.read_aloud
                        TextActionKind.Dict -> R.string.dict
                        TextActionKind.Search -> R.string.search_content
                        TextActionKind.Browser -> R.string.browser
                        TextActionKind.Share -> R.string.share
                        TextActionKind.ProcessText -> R.string.process_text_actions
                    }
                )
            }

    override suspend fun processTargets(): List<TextProcessTarget> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val manager = context.packageManager
        return manager
            .queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
            .map { info ->
                TextProcessTarget(
                    info.activityInfo.packageName,
                    info.activityInfo.name,
                    info.loadLabel(manager).toString(),
                )
            }
    }

    override suspend fun speakMode() = AppConfig.contentSelectSpeakMod

    override suspend fun setSpeakMode(value: Int) {
        AppConfig.contentSelectSpeakMod = value
    }
}
