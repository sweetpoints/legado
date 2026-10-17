package io.legado.app.api.controller


import android.text.TextUtils
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.model.jsSource.JsSourceUpsert.PayloadIssue
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.security.MessageDigest

object BookSourceController {

    private const val JS_SOURCE_SAVE_TIMEOUT_MILLIS = 30_000L

    val sources: ReturnData
        get() {
            val bookSources = appDb.bookSourceDao.all
            val returnData = ReturnData()
            return if (bookSources.isEmpty()) {
                returnData.setErrorMsg("设备源列表为空")
            } else returnData.setData(bookSources)
        }

    fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrNull()
        if (bookSource != null) {
            if (TextUtils.isEmpty(bookSource.bookSourceName) || TextUtils.isEmpty(bookSource.bookSourceUrl)) {
                returnData.setErrorMsg("源名称和URL不能为空")
            } else {
                appDb.bookSourceDao.insert(bookSource)
                returnData.setData("")
            }
        } else {
            returnData.setErrorMsg("转换源失败")
        }
        return returnData
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val okSources = arrayListOf<BookSource>()
        val bookSources = GSON.fromJsonArray<BookSource>(postData).getOrNull()
        if (bookSources.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        bookSources.forEach { bookSource ->
            if (bookSource.bookSourceName.isNotBlank()
                && bookSource.bookSourceUrl.isNotBlank()
            ) {
                appDb.bookSourceDao.insert(bookSource)
                okSources.add(bookSource)
            }
        }
        return ReturnData().setData(okSources)
    }

    suspend fun saveJsSource(
        postData: String?,
        openedSourceUrl: String? = null,
    ): ReturnData {
        val returnData = ReturnData()
        when (JsSourceUpsert.validatePayload(postData)) {
            PayloadIssue.EMPTY -> return returnData.setErrorMsg("数据不能为空")
            PayloadIssue.TOO_LARGE -> return returnData.setErrorMsg("JS源脚本不能超过 1 MiB")
            null -> Unit
        }
        return try {
            val source = JsSourceUpsert.save(
                requireNotNull(postData),
                openedSourceUrl = openedSourceUrl,
                timeoutMillis = JS_SOURCE_SAVE_TIMEOUT_MILLIS,
            )
            returnData.setData(source)
        } catch (error: TimeoutCancellationException) {
            returnData.setErrorMsg("JS源保存超时")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            returnData.setErrorMsg(error.localizedMessage ?: "JS源保存失败")
        }
    }

    internal fun matchesJsSourceApiToken(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = appDb.bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return returnData.setData(bookSource)
    }

    fun deleteSources(postData: String?): ReturnData {
        kotlin.runCatching {
            GSON.fromJsonArray<BookSource>(postData).getOrThrow().let {
                SourceHelp.deleteBookSources(it)
            }
        }.onFailure {
            return ReturnData().setErrorMsg(it.localizedMessage ?: "数据格式错误")
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
