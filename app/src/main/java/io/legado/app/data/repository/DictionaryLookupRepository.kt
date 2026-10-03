package io.legado.app.data.repository

import android.content.Context
import android.util.Base64
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.analyzeRule.AnalyzeUrl
import java.io.File
import kotlinx.coroutines.*

interface DictionaryLookupRepository {
    suspend fun rules(): List<DictionaryRuleSnapshot>

    suspend fun search(rule: DictionaryRuleSnapshot, word: String): String

    suspend fun click(rule: DictionaryRuleSnapshot, name: String, script: String)

    suspend fun image(source: String): DictionaryImageData
}

data class DictionaryImageData(val bytes: ByteArray, val mime: String)

class RoomDictionaryLookupRepository(context: Context, private val database: AppDatabase = appDb) :
    DictionaryLookupRepository {
    private val context = context.applicationContext

    override suspend fun rules() =
        withContext(Dispatchers.IO) {
            database.dictRuleDao.enabled.map(DictionaryRuleSnapshot::from)
        }

    override suspend fun search(rule: DictionaryRuleSnapshot, word: String) =
        withContext(Dispatchers.IO) { rule.entity().search(word) }

    override suspend fun click(rule: DictionaryRuleSnapshot, name: String, script: String) =
        withContext(Dispatchers.IO) {
            try {
                rule.entity().buttonClick(name, script)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("$name click error\n${error.localizedMessage}", error)
            }
        }

    override suspend fun image(source: String): DictionaryImageData {
        var target: FutureTarget<File>? = null
        try {
            return withContext(Dispatchers.IO) {
                if (source.startsWith("data:")) {
                    val matcher = AnalyzeUrl.paramPattern.matcher(source)
                    val data = if (matcher.find()) source.substring(0, matcher.start()) else source
                    val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
                    return@withContext DictionaryImageData(bytes, dictionaryImageMime(bytes))
                }
                withContext(Dispatchers.Main.immediate) {
                    target = ImageLoader.loadFile(context, source).submit()
                }
                val bytes = runInterruptible { checkNotNull(target).get().readBytes() }
                currentCoroutineContext().ensureActive()
                DictionaryImageData(bytes, dictionaryImageMime(bytes))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                target?.let { Glide.with(context).clear(it) }
            }
        }
    }
}

internal fun dictionaryImageMime(bytes: ByteArray): String =
    when {
        bytes.take(3).toByteArray().contentEquals(byteArrayOf(71, 73, 70)) -> "image/gif"
        bytes.size > 1 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size > 1 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.toString(Charsets.UTF_8).trimStart().let {
            it.startsWith("<svg") || it.startsWith("<?xml")
        } -> "image/svg+xml"
        else -> "application/octet-stream"
    }
