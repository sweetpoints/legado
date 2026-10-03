package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.URLUtil
import io.legado.app.constant.AppConst
import io.legado.app.help.TTS
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.ACache
import io.legado.app.utils.writeBytes
import io.legado.app.model.rss.rssReaderSpeechText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.Date

interface RssReaderImageRepository {
    suspend fun directory(): String?
    suspend fun directory(value: String?)
    suspend fun save(image: String, directory: String)
    suspend fun save(image: String, directory: String, fileName: String) = save(image, directory)
}
class AppRssReaderImageRepository(private val context: Context = appCtx,
    private val bytes: suspend (String) -> ByteArray = { value -> okHttpClient.newCallResponseBody { url(value) }.bytes() }) : RssReaderImageRepository {
    override suspend fun directory(): String? = withContext(Dispatchers.IO) { ACache.get().getAsString(AppConst.imagePathKey) }
    override suspend fun directory(value: String?) = withContext(Dispatchers.IO) {
        if (value == null) { ACache.get().remove(AppConst.imagePathKey); Unit } else ACache.get().put(AppConst.imagePathKey, value)
    }
    override suspend fun save(image: String, directory: String) = save(image, directory, "${AppConst.fileNameFormat.format(Date())}.jpg")
    override suspend fun save(image: String, directory: String, fileName: String) = withContext(Dispatchers.IO) {
        require(fileName.isNotBlank() && fileName == java.io.File(fileName).name && fileName != "." && fileName != "..")
        val value = if (URLUtil.isValidUrl(image)) bytes(image) else Base64.decode(image.split(",")[1], Base64.DEFAULT)
        currentCoroutineContext().ensureActive()
        check(Uri.parse(directory).writeBytes(context, fileName, value))
        Unit
    }
}
interface RssReaderSpeechRepository {
    val speaking: StateFlow<Boolean>
    suspend fun speakHtml(encoded: String)
    fun stop()
    fun release()
}
/** TTS owns its Android engine, rather than retaining the reader Activity in a ViewModel callback. */
class AppRssReaderSpeechRepository : RssReaderSpeechRepository {
    private val mutable = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = mutable
    private var tts: TTS? = null
    override suspend fun speakHtml(encoded: String) {
        val text = withContext(Dispatchers.IO) {
            rssReaderSpeechText(encoded)
        }
        withContext(Dispatchers.Main.immediate) {
            val engine = tts ?: TTS().apply {
                setSpeakStateListener(object : TTS.SpeakStateListener {
                    override fun onStart() { mutable.value = true }
                    override fun onDone() { mutable.value = false }
                })
            }.also { tts = it }
            engine.speak(text)
        }
    }
    override fun stop() { tts?.stop(); mutable.value = false }
    override fun release() { tts?.clearTts(); tts = null; mutable.value = false }
}
