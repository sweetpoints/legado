package io.legado.app.utils

import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/** 加密图片解密工具 */
object ImageUtils {

    /**
     * @param isCover 根据这个执行书源中不同的解密规则
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String,
        bytes: ByteArray,
        isCover: Boolean,
        source: BaseSource?,
        book: Book? = null,
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
        return kotlin
            .runCatching {
                decodedBytes(
                    source?.evalJS(
                        """
                        (async () => {
                            const decoded = await eval(imageDecodeRule);
                            if (decoded instanceof ArrayBuffer) return Array.from(new Uint8Array(decoded));
                            if (ArrayBuffer.isView(decoded)) {
                                return Array.from(new Uint8Array(decoded.buffer, decoded.byteOffset, decoded.byteLength));
                            }
                            return decoded;
                        })()
                        """
                            .trimIndent()
                    ) {
                        put("imageDecodeRule", ruleJs)
                        put("book", book)
                        put("result", bytes.map { it.toInt() and 255 })
                        put("src", src)
                    }
                )
            }
            .onFailure {
                if (it is CancellationException) throw it
                AppLog.putDebug("${src}解密错误", it)
            }
            .getOrNull()
    }

    fun decode(
        src: String,
        inputStream: InputStream,
        isCover: Boolean,
        source: BaseSource?,
        book: Book? = null,
    ): InputStream? {
        if (getRuleJs(source, isCover).isNullOrBlank()) return inputStream
        return decode(src, inputStream.readBytes(), isCover, source, book)?.inputStream()
    }

    /** Binary scripts cross the V8 boundary as JSON bytes or an explicit Base64 string. */
    internal fun decodedBytes(value: Any?): ByteArray {
        if (value is String) return Base64.decode(value, Base64.DEFAULT)
        require(value is List<*>) { "Image decode must return a JSON byte array or Base64 string" }
        return ByteArray(value.size) { index ->
            val number =
                value[index] as? Number
                    ?: throw IllegalArgumentException("Image decode byte must be a number")
            val integer = number.toInt()
            require(
                number.toDouble().isFinite() &&
                    number.toDouble() == integer.toDouble() &&
                    integer in -128..255
            ) {
                "Image decode byte must be a signed Java byte or an unsigned byte (−128 through 255)"
            }
            integer.toByte()
        }
    }

    fun skipDecode(source: BaseSource?, isCover: Boolean): Boolean {
        return getRuleJs(source, isCover).isNullOrBlank()
    }

    private fun getRuleJs(
        source: BaseSource?,
        isCover: Boolean,
    ): String? {
        val effectiveSource = source?.getSource() ?: source
        return when (effectiveSource) {
            is BookSource ->
                if (isCover) effectiveSource.coverDecodeJs
                else effectiveSource.getContentRule().imageDecode

            is RssSource -> effectiveSource.coverDecodeJs
            else -> null
        }
    }
}
