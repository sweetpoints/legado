package io.legado.app.data.repository

import androidx.annotation.Keep
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.image.*
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.CoverFontSizes
import io.legado.app.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.File

/** Snapshot caller entities so mutable Book instances cannot change an in-flight request. */
@Keep
data class CoverRequest(val path: String? = null, val name: String? = null, val author: String? = null,
    val loadOnlyWifi: Boolean = false, val sourceOrigin: String? = null) {
    val normalizedPath get() = path?.takeIf { it.isNotBlank() }
    companion object {
        fun from(book: Book, loadOnlyWifi: Boolean = false) = CoverRequest(book.getDisplayCover(), book.name, book.author, loadOnlyWifi, book.getCoverSourceOrigin())
        fun from(book: SearchBook, loadOnlyWifi: Boolean = false) = CoverRequest(book.coverUrl, book.name, book.author, loadOnlyWifi, book.origin)
        fun from(book: BookshelfBook, loadOnlyWifi: Boolean = false) = CoverRequest(book.displayCover, book.name, book.author, loadOnlyWifi, book.coverSourceOrigin)
    }
}
fun normalizeComposeCoverText(value: String?, keepPunctuation: Boolean): String? =
    value?.let { if (keepPunctuation) it.trim() else it.replace(AppPattern.bdRegex, "").trim() }

data class CoverConfiguration(val defaultBitmap: Bitmap, val useDefault: Boolean = false,
    val drawName: Boolean = true, val drawAuthor: Boolean = true, val horizontal: Boolean = false,
    val adaptive: Boolean = true, val keepPunctuation: Boolean = false, val fontSizes: CoverFontSizes? = null,
    val typeface: Typeface? = null, val fontCacheKey: String = "", val backgroundColor: Int = 0,
    val accentColor: Int = 0)

interface CoverRepository {
    val configurations: StateFlow<CoverConfiguration?>
    fun refreshConfiguration()
    suspend fun load(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int): CoverLoadResult
    suspend fun title(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int): Bitmap?
}

/** One configuration/font/default-image owner per application, shared by all visible covers. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlideCoverRepository private constructor(context: Context) : CoverRepository {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val mutableConfigurations = MutableStateFlow<CoverConfiguration?>(null)
    override val configurations = mutableConfigurations.asStateFlow()
    private val loader = GlideCoverImageLoader(this.context)
    private val renderer = CoverTitleRenderer()
    private var currentFontKey: String? = null
    private var currentTypeface: Typeface? = null
    private var currentDefaultKey: String? = null
    private var currentDefault: Bitmap? = null
    init {
        scope.launch {
            merge(preferenceChanges(), refreshes).conflate().onStart { emit(Unit) }.collect {
                runCatching { readConfiguration() }.getOrNull()?.let { mutableConfigurations.value = it }
            }
        }
    }
    override fun refreshConfiguration() { refreshes.tryEmit(Unit) }
    override suspend fun load(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int) = loader.load(request, configuration, width, height)
    override suspend fun title(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int): Bitmap? = withContext(Dispatchers.Default) {
        if (!configuration.drawName || request.name == null || width <= 0 || height <= 0) null else renderer.render(request, configuration, width, height)
    }
    private fun preferenceChanges() = callbackFlow {
        val defaults = context.defaultSharedPreferences
        val themes = ThemeStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        defaults.registerOnSharedPreferenceChangeListener(listener)
        themes.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { defaults.unregisterOnSharedPreferenceChangeListener(listener); themes.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun readConfiguration(): CoverConfiguration {
        val night = when (context.getPrefString(PreferKey.themeMode, "0")) { "1", "3" -> false; "2" -> true; else -> sysConfiguration.isNightMode }
        val fontFile = context.getPrefString(PreferKey.coverFont)?.takeIf { it.isNotBlank() }?.let(::File)
        val fontKey = fontFile?.let { "${it.path}:${it.length()}:${it.lastModified()}" }.orEmpty()
        if (currentFontKey != fontKey) {
            currentTypeface = fontFile?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            currentFontKey = fontKey
        }
        val defaultPath = context.getPrefString(if (night) PreferKey.defaultCoverDark else PreferKey.defaultCover)
        val file = defaultPath?.takeIf { it.isNotBlank() }?.let(::File)
        val defaultKey = file?.let { "${it.path}:${it.length()}:${it.lastModified()}" } ?: "asset"
        if (currentDefaultKey != defaultKey || currentDefault == null) {
            currentDefault = file?.let { runCatching { BitmapUtils.decodeBitmap(it.path, 600, 900) }.getOrNull() }
                ?: checkNotNull(AppCompatResources.getDrawable(context, R.drawable.image_cover_default)).let { source ->
                    val drawable = source.constantState?.newDrawable(context.resources)?.mutate() ?: source
                    Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(Canvas(bitmap))
                    }
                }
            currentDefault = checkNotNull(currentDefault).let { bitmap ->
                if (bitmap.isMutable) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { bitmap.recycle() } else bitmap
            }
            currentDefaultKey = defaultKey
        }
        val fontSizes = if (context.getPrefBoolean(PreferKey.coverCustomFontSize)) CoverFontSizes(
            context.getPrefInt(PreferKey.coverTitleLargeSize, 100).coerceIn(50, 200), context.getPrefInt(PreferKey.coverTitleSmallSize, 100).coerceIn(50, 200),
            context.getPrefInt(PreferKey.coverAuthorLargeSize, 100).coerceIn(50, 200), context.getPrefInt(PreferKey.coverAuthorSmallSize, 100).coerceIn(50, 200)) else null
        return CoverConfiguration(checkNotNull(currentDefault), context.getPrefBoolean(PreferKey.useDefaultCover),
            context.getPrefBoolean(if (night) PreferKey.coverShowNameN else PreferKey.coverShowName, true),
            context.getPrefBoolean(if (night) PreferKey.coverShowAuthorN else PreferKey.coverShowAuthor, true),
            context.getPrefBoolean(PreferKey.coverHorizontal), context.getPrefBoolean(PreferKey.coverTitleAdaptive, true),
            context.getPrefBoolean(PreferKey.coverKeepPunctuation), fontSizes, currentTypeface, fontKey, context.backgroundColor, context.accentColor)
    }
    companion object {
        @Volatile private var instance: GlideCoverRepository? = null
        fun get(context: Context): GlideCoverRepository = instance ?: synchronized(this) {
            instance ?: GlideCoverRepository(context).also { instance = it }
        }
    }
}
