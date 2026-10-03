package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.file.installFontFile
import io.legado.app.data.repository.GlideCoverRepository
import io.legado.app.model.BookCover
import io.legado.app.model.cover.*
import io.legado.app.utils.*
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class AppCoverFontSettingsStore(context: Context) : CoverFontSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.defaultSharedPreferences }

    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == PreferKey.coverFont ||
                    CoverFontSwitch.entries.any { it.key == key } ||
                    CoverFontSize.entries.any { it.key == key }
            )
                trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun load(): CoverFontSettingsSnapshot {
        val values = preferences.all
        return CoverFontSettingsSnapshot(
            CoverFontSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            CoverFontSize.entries.associateWith {
                (values[it.key] as? Int ?: 100).coerceIn(50, 200)
            },
            values[PreferKey.coverFont] as? String ?: "",
        )
    }

    private fun save(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to save cover font settings" }
    }

    override suspend fun boolean(key: CoverFontSwitch, value: Boolean) =
        save(preferences.edit().putBoolean(key.key, value))

    override suspend fun size(key: CoverFontSize, value: Int) =
        save(preferences.edit().putInt(key.key, value.coerceIn(50, 200)))

    override suspend fun stageFont(path: String): String {
        val source = FileDoc.fromFile(path)
        return source.openInputStream().getOrThrow().use { input ->
            installFontFile(
                    input,
                    source.name,
                    File(application.externalFiles, "font"),
                ) {
                    runCatching { Typeface.createFromFile(it) }.isSuccess
                }
                .absolutePath
        }
    }

    override suspend fun font(path: String) =
        save(preferences.edit().putString(PreferKey.coverFont, path))

    override suspend fun refreshCover() {
        BookCover.upDefaultCover()
    }

    override suspend fun refreshPreviewAndBookshelf() {
        GlideCoverRepository.get(application).refreshConfiguration()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }
}
