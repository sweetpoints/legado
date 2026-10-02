package io.legado.app.data.repository

import io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

internal enum class CurlDirection { CurlToAnalyze, AnalyzeToCurl }
internal data class CurlConversionDraft(val input: String = "", val output: String = "",
    val direction: CurlDirection = CurlDirection.CurlToAnalyze, val selectionStart: Int = input.length,
    val selectionEnd: Int = selectionStart, val revision: Long = 0)
internal interface CurlDraftStore {
    suspend fun read(session: String): CurlConversionDraft?
    suspend fun write(session: String, draft: CurlConversionDraft)
    suspend fun initial(inputKey: String?): String
}
internal interface CurlConversionRepository {
    suspend fun restore(session: String, inputKey: String?): CurlConversionDraft
    suspend fun save(session: String, draft: CurlConversionDraft)
    suspend fun convert(input: String, direction: CurlDirection): String
}
internal class DefaultCurlConversionRepository(private val store: CurlDraftStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default) : CurlConversionRepository {
    private val seeds = ConcurrentHashMap<String, CurlConversionDraft>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private fun lock(session: String) = sessionLocks.getOrPut(session) { Mutex() }
    override suspend fun restore(session: String, inputKey: String?) = withContext(io) {
        lock(session).withLock {
            val existing = store.read(session)
            if (existing != null) { seeds.remove(session); existing }
            else {
                val draft = seeds[session] ?: store.initial(inputKey).let { input ->
                    CurlConversionDraft(input = input, direction = if (input.isNotBlank() && !CurlAnalyzeUrlConverter.looksLikeCurl(input))
                        CurlDirection.AnalyzeToCurl else CurlDirection.CurlToAnalyze).also { seeds[session] = it }
                }
                // IntentData is a consuming read. Keep the seed until it is safely durable so IO retry does not start blank.
                store.write(session, draft); seeds.remove(session); draft
            }
        }
    }
    override suspend fun save(session: String, draft: CurlConversionDraft) = withContext(io) {
        lock(session).withLock { store.write(session, draft); seeds.remove(session); Unit }
    }
    override suspend fun convert(input: String, direction: CurlDirection) = withContext(compute) {
        coroutineContext.ensureActive()
        val result = when (direction) {
            CurlDirection.CurlToAnalyze -> CurlAnalyzeUrlConverter.curlToAnalyzeUrl(input)
            CurlDirection.AnalyzeToCurl -> CurlAnalyzeUrlConverter.analyzeUrlToCurl(input)
        }
        coroutineContext.ensureActive(); result
    }
}
