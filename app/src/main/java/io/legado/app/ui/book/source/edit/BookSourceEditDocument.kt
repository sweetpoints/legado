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
            return BookSourceEditDocument(originalKey, GSON.toJson(source), form)
        }
    }
}
