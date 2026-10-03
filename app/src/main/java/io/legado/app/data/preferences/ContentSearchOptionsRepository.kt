package io.legado.app.data.preferences

internal data class ContentSearchOptions(val replace: Boolean = false, val regex: Boolean = false)
internal interface ContentSearchOptionsRepository {
    fun current(): ContentSearchOptions
    fun replace(value: Boolean): ContentSearchOptions
    fun regex(value: Boolean): ContentSearchOptions
    fun restore(value: ContentSearchOptions): ContentSearchOptions
}
/** The original two companion flags live for the process, rather than becoming reader preferences. */
internal object ProcessContentSearchOptionsRepository : ContentSearchOptionsRepository {
    @Volatile private var value = ContentSearchOptions()
    override fun current() = value
    @Synchronized override fun replace(value: Boolean) = this.value.copy(replace = value).also { this.value = it }
    @Synchronized override fun regex(value: Boolean) = this.value.copy(regex = value).also { this.value = it }
    @Synchronized override fun restore(value: ContentSearchOptions) = value.also { this.value = it }
}
