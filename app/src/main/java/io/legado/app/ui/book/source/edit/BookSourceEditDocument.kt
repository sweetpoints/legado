package io.legado.app.ui.book.source.edit

import androidx.annotation.Keep
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

@Keep
internal enum class BookSourceSaveAction {
    FINISH,
    DEBUG,
    LOGIN,
    SEARCH,
    VARIABLE,
}

@Keep
internal data class BookSourceSaveDelivery(
    val id: String,
    val action: BookSourceSaveAction,
    val sourceUrl: String,
)

@Keep
internal enum class BookSourceNativeAction {
    EDITOR,
    QR,
    FILE,
    JS,
    DEBUG,
    LOGIN,
    SEARCH,
    COPY,
    SHARE,
    QR_SHARE,
    HELP,
    LOG,
    URL_OPTIONS,
    KEYBOARD_CONFIG,
}

@Keep
internal data class BookSourceNativeRequest(
    val id: String,
    val action: BookSourceNativeAction,
    val delivered: Boolean = false,
    val handedOff: Boolean = false,
    val text: String? = null,
    val path: String? = null,
    val sourceUrl: String? = null,
    val tab: Int = 0,
    val key: String? = null,
    val cursor: Int = 0,
    val selectionEnd: Int = cursor,
    val returning: Boolean = false,
    val returnedText: String? = null,
    val returnedPath: String? = null,
    val returnedCursor: Int = -1,
)

@Keep
internal data class BookSourceFieldHistory(
    val tab: Int,
    val key: String,
    val undo: List<BookSourceEditField> = emptyList(),
    val redo: List<BookSourceEditField> = emptyList(),
)

@Keep
internal data class BookSourceEditDocument(
    val originalKey: String?,
    val originalJson: String,
    val form: BookSourceEditForm,
    val baseline: BookSourceEditForm = form,
    val selectedTab: Int = 0,
    val focusedKey: String? = null,
    val optionsExpanded: Boolean = false,
    val autoComplete: Boolean = false,
    val revision: Long = 0,
    val finished: Boolean = false,
    val delivery: BookSourceSaveDelivery? = null,
    val nativeRequest: BookSourceNativeRequest? = null,
    val ownedTransfers: List<String> = emptyList(),
    val histories: List<BookSourceFieldHistory> = emptyList(),
    val savedUrl: String? = null,
    val variableDraft: String? = null,
    val variableComment: String? = null,
    val redirectJs: Boolean = false,
    val importPayload: String? = null,
    val helpShown: Boolean = false,
) {
    fun original(): BookSource = GSON.fromJsonObject<BookSource>(originalJson).getOrThrow()

    fun source(): BookSource = materializeBookSourceEditForm(original(), form, autoComplete)

    fun dirty(): Boolean {
        return form.options != baseline.options ||
            form.tabs.map { fields -> fields.map { it.value } } !=
                baseline.tabs.map { fields -> fields.map { it.value } }
    }

    companion object {
        fun from(
            source: BookSource,
            originalKey: String? = source.bookSourceUrl,
        ): BookSourceEditDocument {
            val form = projectBookSourceEditForm(source)
            return BookSourceEditDocument(
                originalKey,
                GSON.toJson(source),
                form,
                redirectJs = source.isJsSource(),
            )
        }
    }
}
