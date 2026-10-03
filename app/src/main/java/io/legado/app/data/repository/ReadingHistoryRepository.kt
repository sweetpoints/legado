package io.legado.app.data.repository

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.cnCompare
import io.legado.app.utils.getInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putInt
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReadingHistoryIdentity(val name: String, val author: String)

data class ReadingHistoryCover(
    val current: String?,
    val snapshot: String?,
    val sourceOrigin: String?,
    val onlyWifi: Boolean,
)

data class ReadingHistoryRow(
    val identity: ReadingHistoryIdentity,
    val displayAuthor: String,
    val legacyAuthors: List<String>,
    val combined: Boolean,
    val readTime: Long,
    val lastRead: Long,
    val chapter: String?,
    val cover: ReadingHistoryCover,
) {
    // Length framing avoids collisions when author or title contain separators.
    val key = "${identity.name.length}:${identity.name}${identity.author}"
}

data class ReadingHistorySnapshot(
    val rows: List<ReadingHistoryRow>,
    val count: Int,
    val total: Long,
    val top: List<ReadingHistoryRow>,
)

data class ReadingHistoryPreferences(
    val enabled: Boolean = true,
    val simple: Boolean = true,
    val days: Boolean = false,
    val seconds: Boolean = true,
    val fixed: Boolean = true,
    val sort: Int = 0,
    val fallback: String? = null,
)

enum class ReadingHistoryPreference {
    Enabled,
    Simple,
    Days,
    Seconds,
    Fixed,
    Sort,
}

enum class ReadingHistoryReader {
    Text,
    Audio,
    Video,
    Manga,
    Search,
}

data class ReadingHistoryDestination(
    val kind: ReadingHistoryReader,
    val key: String,
    val name: String,
)

interface ReadingHistoryRepository {
    suspend fun load(query: String, sort: Int): ReadingHistorySnapshot

    suspend fun preferences(): ReadingHistoryPreferences

    suspend fun preferences(value: ReadingHistoryPreferences, fields: Set<ReadingHistoryPreference>)

    suspend fun clear()

    suspend fun delete(identity: ReadingHistoryIdentity)

    suspend fun removeAuthor(identity: ReadingHistoryIdentity, author: String)

    suspend fun destination(identity: ReadingHistoryIdentity): ReadingHistoryDestination
}

/** Queries preserve the DAO's author encoding and cross-device aggregation verbatim. */
class RoomReadingHistoryRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val prune: () -> Unit = ReadRecordCoverCache::prune,
) : ReadingHistoryRepository {
    private val context = context.applicationContext
    private val writes = Mutex()

    override suspend fun load(query: String, sort: Int): ReadingHistorySnapshot =
        withContext(IO) {
            var snapshot: ReadingHistorySnapshot? = null
            database.runInTransaction {
                val books =
                    database.bookDao.all
                        .sortedBy { it.durChapterTime }
                        .associateBy { it.name to it.author }
                val all = database.readRecordDao.allShow
                val records = if (query.isBlank()) all else database.readRecordDao.search(query)
                val onlyWifi = AppConfig.loadCoverOnlyWifi
                fun row(record: io.legado.app.data.entities.ReadRecordShow): ReadingHistoryRow {
                    val book = books[record.bookName to record.author]
                    return ReadingHistoryRow(
                        ReadingHistoryIdentity(record.bookName, record.author),
                        record.displayAuthor,
                        ReadRecordAuthors.decode(record.author).toList(),
                        record.hasCombinedAuthors,
                        record.readTime,
                        record.lastRead,
                        book?.durChapterTitle?.takeIf { it.isNotBlank() }
                            ?: record.lastChapterTitle?.takeIf { it.isNotBlank() },
                        ReadingHistoryCover(
                            book?.getDisplayCover()?.takeIf { it.isNotBlank() } ?: record.coverUrl,
                            record.coverUrl,
                            book?.getCoverSourceOrigin(),
                            onlyWifi,
                        ),
                    )
                }
                val sorted =
                    when (sort) {
                        1 -> records.sortedByDescending { it.readTime }
                        2 -> records.sortedByDescending { it.lastRead }
                        else ->
                            records.sortedWith { a, b ->
                                a.bookName.cnCompare(b.bookName).takeIf { it != 0 }
                                    ?: a.displayAuthor.cnCompare(b.displayAuthor)
                            }
                    }
                snapshot =
                    ReadingHistorySnapshot(
                        sorted.map(::row),
                        all.size,
                        all.sumOf { it.readTime },
                        all.sortedByDescending { it.readTime }.take(3).map(::row),
                    )
            }
            checkNotNull(snapshot)
        }

    override suspend fun preferences() =
        withContext(IO) {
            ReadingHistoryPreferences(
                AppConfig.enableReadRecord,
                AppConfig.readRecordSimpleLayout,
                AppConfig.readRecordUseDays,
                AppConfig.readRecordShowSeconds,
                AppConfig.readRecordFixedCard,
                LocalConfig.getInt("readRecordSort"),
                context.getPrefString(
                    if (AppConfig.isNightTheme) PreferKey.readRecordCoverDark
                    else PreferKey.readRecordCover
                ),
            )
        }

    override suspend fun preferences(
        value: ReadingHistoryPreferences,
        fields: Set<ReadingHistoryPreference>,
    ) =
        withContext(IO) {
            writes.withLock {
                if (ReadingHistoryPreference.Enabled in fields)
                    AppConfig.enableReadRecord = value.enabled
                if (ReadingHistoryPreference.Simple in fields)
                    AppConfig.readRecordSimpleLayout = value.simple
                if (ReadingHistoryPreference.Days in fields)
                    AppConfig.readRecordUseDays = value.days
                if (ReadingHistoryPreference.Seconds in fields)
                    AppConfig.readRecordShowSeconds = value.seconds
                if (ReadingHistoryPreference.Fixed in fields)
                    AppConfig.readRecordFixedCard = value.fixed
                if (ReadingHistoryPreference.Sort in fields)
                    LocalConfig.putInt("readRecordSort", value.sort)
            }
        }

    override suspend fun clear() =
        withContext(IO) {
            writes.withLock {
                database.readRecordDao.clear()
                prune()
            }
        }

    override suspend fun delete(identity: ReadingHistoryIdentity) =
        withContext(IO) {
            writes.withLock {
                database.readRecordDao.deleteByBook(identity.name, identity.author)
                prune()
            }
        }

    override suspend fun removeAuthor(identity: ReadingHistoryIdentity, author: String) =
        withContext(IO) {
            writes.withLock {
                database.readRecordDao.removeLegacyAuthor(identity.name, identity.author, author)
                prune()
            }
        }

    override suspend fun destination(identity: ReadingHistoryIdentity) =
        withContext(IO) {
            val book =
                database.bookDao
                    .findByName(identity.name)
                    .filter { it.author == identity.author }
                    .maxByOrNull { it.durChapterTime }
            if (book == null)
                ReadingHistoryDestination(ReadingHistoryReader.Search, "", identity.name)
            else
                ReadingHistoryDestination(
                    when {
                        book.isVideo -> ReadingHistoryReader.Video
                        book.isAudio -> ReadingHistoryReader.Audio
                        !book.isLocal && book.isImage && AppConfig.showMangaUi ->
                            ReadingHistoryReader.Manga
                        else -> ReadingHistoryReader.Text
                    },
                    book.bookUrl,
                    identity.name,
                )
        }
}
