package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BookMetadataReaderRepository {
    suspend fun loadHighlights(bookUrl:String):List<BookHighlight>
}

/** Reader publication belongs to the host; Room preparation always runs outside Main. */
class RoomBookMetadataReaderRepository(private val database:AppDatabase=appDb):BookMetadataReaderRepository {
    override suspend fun loadHighlights(bookUrl:String):List<BookHighlight> = withContext(Dispatchers.IO) {
        database.bookHighlightDao.getByBook(bookUrl).map{it.copy()}
    }
}
