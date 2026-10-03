package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.config.AppConfig

internal interface AudioSkipCreditsPreferences {
    fun read(): Pair<Int, Int>
    fun write(opening: Int, closing: Int)
}
internal class AppAudioSkipCreditsPreferences : AudioSkipCreditsPreferences {
    override fun read() = AppConfig.audioSkipOpenCredits to AppConfig.audioSkipCloseCredits
    override fun write(opening: Int, closing: Int) { AppConfig.audioSkipOpenCredits = opening; AppConfig.audioSkipCloseCredits = closing }
}
internal class AppAudioSkipCreditsStore(override val bookId: String, seed: Book? = null,
    private val database: AppDatabase = appDb, private val preferences: AudioSkipCreditsPreferences = AppAudioSkipCreditsPreferences(),
    private val save: (Book) -> Unit = { it.savePreservingCustomCoverUrl() }) : AudioSkipCreditsStore {
    private val seed = seed?.copy(readConfig = seed.readConfig?.copy())
    override suspend fun load(): AudioSkipCreditsDraft {
        val book = seed ?: database.bookDao.getBook(bookId) ?: error("书籍不存在，请重新打开播放页面")
        val globals = preferences.read(); val config = book.config
        return AudioSkipCreditsDraft(config.useGlobalAudioSkip, config.openCredits, config.closeCredits, globals.first, globals.second)
    }
    override suspend fun globals(opening: Int, closing: Int) { preferences.write(opening, closing) }
    override suspend fun saveBook(draft: AudioSkipCreditsDraft) {
        val current = database.bookDao.getBook(bookId) ?: seed?.copy(readConfig = seed.readConfig?.copy()) ?: error("书籍不存在，请重新打开播放页面")
        current.config.apply { useGlobalAudioSkip = draft.useGlobal; openCredits = draft.bookOpen; closeCredits = draft.bookClose }
        save(current)
    }
}
