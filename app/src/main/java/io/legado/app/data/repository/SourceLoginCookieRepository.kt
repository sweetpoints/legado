package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SourceLoginCookieRepository {
    suspend fun store(sourceKey: String, cookie: String?)
}

/** Same domain/cache protocol as CookieStore, with failures visible to the completion owner. */
class AppSourceLoginCookieRepository(
    private val database: AppDatabase = appDb,
    private val beforeWrite: (String, String) -> Unit = { _, _ -> },
) : SourceLoginCookieRepository {
    override suspend fun store(sourceKey: String, cookie: String?): Unit =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val domain = NetworkUtils.getSubDomain(sourceKey)
                val value = cookie.orEmpty()
                beforeWrite(domain, value)
                currentCoroutineContext().ensureActive()
                database.cookieDao.insert(Cookie(domain, value))
                CacheManager.putMemory("${domain}_cookie", value)
            }
        }

    private companion object {
        val gate = Mutex()
    }
}
