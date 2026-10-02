package io.legado.app.data.repository

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun chapterSourceSessionSeed(name: String, author: String, index: Int, title: String,
    batch: Boolean, book: Book?, fromReader: Boolean): ChapterSourceSession = withContext(Dispatchers.IO) {
    ChapterSourceSession(ChapterSourceSearchRequest(name, author.replace(AppPattern.authorRegex, ""),
        originalBookJson = book?.let { GSON.toJson(it) }, fromReader = fromReader, currentBookUrl = book?.bookUrl), index, title, batch)
}
