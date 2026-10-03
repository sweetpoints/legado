package io.legado.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppThemeListStoreTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun config(name: String) =
        ThemeConfig.Config(name, false, "#123456", "#654321", "#FFFFFF", "#EEEEEE", false, null, 0)

    private suspend fun preserveThemes(block: suspend () -> Unit) {
        val original = withContext(Dispatchers.IO) { ThemeConfig.snapshotConfigs() }
        val file = File(ThemeConfig.configFilePath)
        val bytes = withContext(Dispatchers.IO) { if (file.exists()) file.readBytes() else null }
        try {
            block()
        } finally {
            withContext(Dispatchers.IO) {
                synchronized(ThemeConfig) {
                    ThemeConfig.configList.clear()
                    ThemeConfig.configList.addAll(original)
                    if (bytes == null) file.delete() else file.writeBytes(bytes)
                    File(file.path + ".bak").delete()
                    File(file.path + ".new").delete()
                }
            }
        }
    }

    @Test
    fun actualAddNameReplacementExactDeleteAndConcurrentSnapshotWritesRemainConsistent() =
        runBlocking {
            preserveThemes {
                val token = UUID.randomUUID().toString()
                val repo = DefaultThemeListRepository(AppThemeListStore(context))
                val old = config("old-$token")
                val other = config("other-$token")
                repo.add(GSON.toJson(old))
                repo.add(GSON.toJson(other))
                val captured = repo.list().first { it.name == old.themeName }
                val changed = old.copy(backgroundImgBlur = 7, primaryColor = "#ABCDEF")
                repo.add(GSON.toJson(changed))
                assertFalse(repo.delete(captured))
                assertEquals(1, repo.list().count { it.name == old.themeName })
                val current = repo.list().first { it.name == old.themeName }
                assertTrue(repo.delete(current))
                assertTrue(repo.list().any { it.name == other.themeName })
                assertFalse(repo.add("invalid"))
                assertFalse(repo.add(GSON.toJson(old.copy(primaryColor = "wrong"))))
                coroutineScope {
                    (1..40)
                        .map { index ->
                            async(Dispatchers.IO) {
                                ThemeConfig.addConfig(config("parallel-$token-$index"))
                                ThemeConfig.snapshotConfigs()
                            }
                        }
                        .awaitAll()
                }
                val rows = repo.list()
                assertEquals(40, rows.count { it.name.startsWith("parallel-$token-") })
                val disk =
                    GSON.fromJson(
                            File(ThemeConfig.configFilePath).readText(),
                            Array<ThemeConfig.Config>::class.java,
                        )
                        .toList()
                assertEquals(ThemeConfig.snapshotConfigs(), disk)
            }
        }

    @Test
    fun actualShareFileKeepsEntireJsonAcrossInstancesAndListChanges() = runBlocking {
        val session = UUID.randomUUID().toString()
        val receipt = UUID.randomUUID().toString()
        val file = File(context.filesDir, "theme-list-shares/$session-$receipt.json")
        try {
            val store = AppThemeListStore(context)
            val payload =
                GSON.toJson(
                    config("Share")
                        .copy(
                            backgroundImgPath = "x".repeat(1200000),
                            isNightTheme = true,
                            transparentNavBar = true,
                            backgroundImgBlur = 9,
                        )
                )
            store.writeShare(session, receipt, payload)
            assertEquals(payload, AppThemeListStore(context).readShare(session, receipt))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    @Test
    fun asyncThemeApplyPreparesPreferencesOffMainAndCommitsThemeStoreOnMain() = runBlocking {
        val token = UUID.randomUUID().toString()
        val external = File(context.cacheDir, "theme-apply-$token").apply { mkdirs() }
        val threads = ConcurrentHashMap<String, Thread>()
        val probe =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getExternalFilesDir(type: String?): File = external

                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val original = super.getSharedPreferences("theme-probe-$token-$name", mode)
                    return object : SharedPreferences by original {
                        override fun edit(): SharedPreferences.Editor {
                            val delegate = original.edit()
                            return object : SharedPreferences.Editor by delegate {
                                override fun putInt(
                                    key: String,
                                    value: Int,
                                ): SharedPreferences.Editor {
                                    threads[key.orEmpty()] = Thread.currentThread()
                                    delegate.putInt(key, value)
                                    return this
                                }

                                override fun putLong(
                                    key: String,
                                    value: Long,
                                ): SharedPreferences.Editor {
                                    threads[key.orEmpty()] = Thread.currentThread()
                                    delegate.putLong(key, value)
                                    return this
                                }
                            }
                        }
                    }
                }
            }
        val mode = context.getPrefString(PreferKey.themeMode)
        try {
            ThemeConfig.applyConfigAsync(probe, config("Apply"))
            assertNotNull(threads[PreferKey.cPrimary])
            assertNotSame(Looper.getMainLooper().thread, threads[PreferKey.cPrimary])
            assertSame(Looper.getMainLooper().thread, threads["values_changed"])
        } finally {
            withContext(Dispatchers.IO) {
                context.putPrefString(PreferKey.themeMode, mode)
                BookCover.upDefaultCover()
                external.deleteRecursively()
            }
            withContext(Dispatchers.Main) { ThemeConfig.applyDayNightInit(context) }
        }
    }
}
