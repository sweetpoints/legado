package io.legado.app.ui.book.read

internal data class ContentDraftRequest(val generation: Long, val revision: Long)

internal data class ContentEditTarget(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterPos: Int,
) {
    fun matches(bookUrl: String?, chapterIndex: Int): Boolean {
        return this.bookUrl == bookUrl && this.chapterIndex == chapterIndex
    }
}

internal class ContentDraftState {
    var text: String? = null
        private set

    private var baseline: String? = null
    private var restoredChanges = false
    private var revision = 0L
    private var requestGeneration = 0L

    val hasDraft: Boolean
        get() = text != null

    val hasChanges: Boolean
        get() = restoredChanges || (text != null && text != baseline)

    fun restore(text: String, hasChanges: Boolean = false): Boolean {
        if (this.text != null) return false
        this.text = text
        baseline = text
        restoredChanges = hasChanges
        return true
    }

    fun update(text: String): Boolean {
        if (this.text == text) return false
        this.text = text
        revision++
        return true
    }

    fun newRequest(): ContentDraftRequest {
        return ContentDraftRequest(++requestGeneration, revision)
    }

    fun applyLoaded(request: ContentDraftRequest, text: String): String? {
        if (request.generation != requestGeneration || request.revision != revision) return null
        if (this.text != text) revision++
        this.text = text
        baseline = text
        restoredChanges = false
        return text
    }
}
