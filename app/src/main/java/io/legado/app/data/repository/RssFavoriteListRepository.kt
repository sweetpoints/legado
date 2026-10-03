package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** List projection deliberately excludes article content and mutable Room entities. */
data class RssFavoriteRow(
    val id: String,
    val group: String,
    val title: String,
    val pubDate: String?,
    val image: String?,
    val origin: String,
    val starTime: Long,
)

data class RssFavoriteListSnapshot(val groups: List<String>, val rows: List<RssFavoriteRow>)

interface RssFavoriteListRepository {
    fun observe(): Flow<RssFavoriteListSnapshot>

    suspend fun resolve(id: String): RssStar?

    suspend fun delete(id: String)

    suspend fun deleteGroup(group: String)

    suspend fun deleteAll()
}

class RoomRssFavoriteListRepository(private val database: AppDatabase = appDb) :
    RssFavoriteListRepository {
    override fun observe() =
        database.rssStarDao
            .liveAll()
            .map { stars ->
                RssFavoriteListSnapshot(
                    stars.map { it.group }.distinct().sorted(),
                    stars.map(::row),
                )
            }
            .flowOn(Dispatchers.IO)

    // Resolve again immediately before reading/deleting; list rows never retain large article
    // bodies.
    override suspend fun resolve(id: String): RssStar? =
        withContext(Dispatchers.IO) {
            database.rssStarDao.all
                .firstOrNull { key(it.origin, it.link) == id }
                ?.let {
                    database.rssStarDao.get(it.origin, it.link)
                }
        }

    override suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            resolve(id)?.let { database.rssStarDao.delete(it.origin, it.link) }
            Unit
        }

    override suspend fun deleteGroup(group: String) =
        withContext(Dispatchers.IO) { database.rssStarDao.deleteByGroup(group) }

    override suspend fun deleteAll() =
        withContext(Dispatchers.IO) { database.rssStarDao.deleteAll() }

    companion object {
        fun key(origin: String, link: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("${origin.length}:$origin${link.length}:$link".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun row(star: RssStar) =
            RssFavoriteRow(
                key(star.origin, star.link),
                star.group,
                star.title,
                star.pubDate,
                star.image,
                star.origin,
                star.starTime,
            )
    }
}
