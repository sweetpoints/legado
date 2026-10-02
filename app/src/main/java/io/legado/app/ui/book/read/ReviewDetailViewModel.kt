package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

internal enum class ReviewDetailAction { Photo, Audio, Toast }
internal data class ReviewDetailEffect(val id: Long, val action: ReviewDetailAction, val rowKey: String = "", val message: String = "")
internal data class ReviewDetailRow(val key: String, val parent: String, val comment: ReviewComment? = null,
    val reply: Boolean = false, val more: Int = 0, val loading: Boolean = false)
internal data class ReviewDetailState(val snapshot: ReviewDetailSnapshot, val loading: Boolean = true,
    val loadingReplies: Set<String> = emptySet(), val error: String? = null, val finished: Boolean = false,
    val heightRatio: Float = .68f, val effects: List<ReviewDetailEffect> = emptyList()) {
    val rows: List<ReviewDetailRow> get() = buildList {
        snapshot.items.forEach { item ->
            val parent = reviewCommentKey(item, false)
            add(ReviewDetailRow(stableReviewRowKey(parent), parent, item))
            val loaded = item.replies.size
            val total = max(item.replyCount ?: 0, loaded)
            val expanded = loaded > 0 || parent in snapshot.expanded
            val canLoad = snapshot.hasReplies && !item.id.isNullOrBlank() && parent !in snapshot.exhausted && (item.replyCount ?: 0) > loaded
            if (expanded) item.replies.forEach { reply -> add(ReviewDetailRow(stableReviewRowKey("$parent/${reviewCommentKey(reply, true)}"), parent, reply, reply = true)) }
            if ((!expanded && loaded <= 0 && !canLoad) || expanded && !canLoad) return@forEach
            add(ReviewDetailRow(stableReviewRowKey("$parent/more"), parent, more = if (expanded) total - loaded else total, loading = parent in loadingReplies))
        }
    }
}
internal class ReviewDetailViewModel(private val repository: ReviewDetailRepository, private val saved: SavedStateHandle,
    private val key: ReviewDetailKey, initialHeightRatio: Float = .68f) : ViewModel() {
    private val session = saved.get<String>("session") ?: UUID.randomUUID().toString().also { saved["session"] = it }
    private val mutable = MutableStateFlow(ReviewDetailState(ReviewDetailSnapshot(key),
        finished = saved["finished"] ?: false, heightRatio = (saved.get<Float>("heightRatio") ?: initialHeightRatio).coerceIn(.35f, .92f),
        effects = saved.get<String>("effects")?.let { GSON.fromJsonArray<ReviewDetailEffect>(it).getOrNull() }.orEmpty()))
    val state = mutable.asStateFlow()
    private var mainJob: Job? = null
    private val replyJobs = mutableMapOf<String, Job>()
    private val cacheMutex = Mutex()
    private var nextEffect = saved.get<Long>("effectId") ?: 0L
    private val pendingReplies: MutableMap<String, Int> = saved.get<String>("pendingReplies")
        ?.let { GSON.fromJsonObject<Map<String, Int>>(it).getOrNull() }?.toMutableMap() ?: mutableMapOf()
    init {
        if (state.value.finished) mutable.value = state.value.copy(loading = false)
        else mainJob = viewModelScope.launch {
            try {
                val cached = repository.restore(session, key)
                if (state.value.finished) return@launch
                if (cached != null) mutable.value = state.value.copy(snapshot = cached, loading = false)
                val page = saved.get<Int>("pendingPage") ?: if (cached == null) 1 else 0
                if (page > (cached?.page ?: 0)) loadPage(page)
                else { saved.remove<Int>("pendingPage"); mutable.value = state.value.copy(loading = false) }
                pendingReplies.toMap().forEach { (parent, replyPage) ->
                    if ((state.value.snapshot.replyPages[parent] ?: 0) < replyPage) requestReplies(parent, replyPage)
                    else { pendingReplies.remove(parent); savePendingReplies() }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error, initial = true) }
        }
    }
    private fun fail(error: Exception, initial: Boolean) {
        if (state.value.finished) return
        mutable.value = state.value.copy(loading = false, error = if (initial) error.localizedMessage.orEmpty() else state.value.error)
        if (!initial) effect(ReviewDetailAction.Toast, message = error.localizedMessage.orEmpty())
    }
    fun retry() { if (!state.value.finished && !state.value.loading) startPage(if (state.value.snapshot.page == 0) 1 else state.value.snapshot.page + 1) }
    fun nextPage() {
        val value = state.value
        if (!value.finished && !value.loading && value.snapshot.hasMore) startPage(value.snapshot.page + 1)
    }
    private fun startPage(page: Int) {
        if (state.value.finished || state.value.loading) return
        mutable.value = state.value.copy(loading = true, error = null)
        saved["pendingPage"] = page
        mainJob = viewModelScope.launch { loadPage(page) }
    }
    private suspend fun loadPage(page: Int) {
        saved["pendingPage"] = page
        mutable.value = state.value.copy(loading = true, error = null)
        var completed = false
        try {
            val result = repository.detail(key, page, state.value.snapshot.nextPageUrl)
            cacheMutex.withLock {
                if (state.value.finished) return@withLock
                val previous = state.value.snapshot
                val incoming = result?.items.orEmpty()
                val merged = mergeReviewDetails(previous.items, incoming)
                val next = previous.copy(items = merged.items, page = page,
                    nextPageUrl = if (result?.hasNextPageRule == true) result.nextPageUrl else previous.nextPageUrl,
                    hasReplies = result?.hasReplyUrl ?: previous.hasReplies,
                    hasMore = incoming.isNotEmpty() && !(page > 1 && merged.changes == 0) &&
                        (result?.hasNextPageRule != true || !result.nextPageUrl.isNullOrBlank()))
                repository.stage(session, next)
                if (!state.value.finished) mutable.value = state.value.copy(snapshot = next, loading = false)
            }
            completed = true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { fail(error, initial = page == 1); completed = true }
        finally { if (completed) saved.remove<Int>("pendingPage") }
    }
    fun replies(parent: String) {
        val value = state.value
        if (value.finished || !value.snapshot.hasReplies || parent in value.loadingReplies || parent in value.snapshot.exhausted) return
        requestReplies(parent, (value.snapshot.replyPages[parent] ?: 0) + 1)
    }
    private fun savePendingReplies() { saved["pendingReplies"] = GSON.toJson(pendingReplies) }
    private fun requestReplies(parent: String, page: Int) {
        val item = state.value.snapshot.items.find { reviewCommentKey(it, false) == parent } ?: return
        val id = item.id?.takeIf { it.isNotBlank() } ?: return
        if (state.value.finished || parent in state.value.loadingReplies) return
        pendingReplies[parent] = page; savePendingReplies()
        mutable.value = state.value.copy(loadingReplies = state.value.loadingReplies + parent)
        replyJobs[parent] = viewModelScope.launch {
            var completed = false
            try {
                val result = repository.replies(key, id, page)
                if (result == null) { effect(ReviewDetailAction.Toast, message = "review_rule_missing") }
                else cacheMutex.withLock {
                    if (state.value.finished) return@withLock
                    val previous = state.value.snapshot
                    val current = previous.items.find { reviewCommentKey(it, false) == parent } ?: return@withLock
                    val replies = mergeReviewReplies(current.replies, result.replies)
                    val exhausted = result.replies.isEmpty() || replies.size == current.replies.size ||
                        current.replyCount != null && replies.size >= current.replyCount
                    val next = previous.copy(items = previous.items.map { if (reviewCommentKey(it, false) == parent) current.copy(replies = replies) else it },
                        replyPages = previous.replyPages + (parent to result.page), expanded = previous.expanded + parent,
                        exhausted = if (exhausted) previous.exhausted + parent else previous.exhausted)
                    repository.stage(session, next)
                    if (!state.value.finished) mutable.value = state.value.copy(snapshot = next)
                }
                completed = true
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (!state.value.finished) effect(ReviewDetailAction.Toast, message = error.localizedMessage.orEmpty()); completed = true }
            finally {
                if (completed) { pendingReplies.remove(parent); savePendingReplies() }
                if (!state.value.finished) mutable.value = state.value.copy(loadingReplies = state.value.loadingReplies - parent)
                replyJobs.remove(parent)
            }
        }
    }
    fun photo(row: String) { state.value.rows.find { it.key == row }?.comment?.imageUrl?.takeIf { it.isNotBlank() }?.let { effect(ReviewDetailAction.Photo, row) } }
    fun audio(row: String) {
        val url = state.value.rows.find { it.key == row }?.comment?.audioUrl?.takeIf { it.isNotBlank() } ?: return
        if (state.value.effects.none { it.action == ReviewDetailAction.Audio && effectUrl(it) == url }) effect(ReviewDetailAction.Audio, row)
    }
    fun effectUrl(effect: ReviewDetailEffect): String? = state.value.rows.find { it.key == effect.rowKey }?.comment?.let {
        if (effect.action == ReviewDetailAction.Photo) it.imageUrl else it.audioUrl
    }
    suspend fun mediaItem(url: String) = repository.mediaItem(key, url)
    private fun effect(action: ReviewDetailAction, rowKey: String = "", message: String = "") {
        if (state.value.finished) return
        nextEffect++; saved["effectId"] = nextEffect
        mutable.value = state.value.copy(effects = state.value.effects + ReviewDetailEffect(nextEffect, action, rowKey, message)); saveEffects()
    }
    private fun saveEffects() { saved["effects"] = GSON.toJson(state.value.effects) }
    fun consumeEffect(id: Long) { mutable.value = state.value.copy(effects = state.value.effects.filterNot { it.id == id }); saveEffects() }
    fun resize(deltaRatio: Float) { height((state.value.heightRatio + deltaRatio).coerceIn(.35f, .92f)) }
    fun toggleHeight() { height(if (state.value.heightRatio < (.68f + .92f) / 2f) .92f else .68f) }
    private fun height(value: Float) { if (!state.value.finished) { saved["heightRatio"] = value; mutable.value = state.value.copy(heightRatio = value) } }
    fun cancel() {
        saved["finished"] = true; saved["effects"] = "[]"
        mutable.value = state.value.copy(finished = true, loading = false, loadingReplies = emptySet(), effects = emptyList())
        mainJob?.cancel(); replyJobs.values.toList().forEach { it.cancel() }
    }
}

// Comment identities can include data images; SavedState/LazyList keys keep only a stable digest.
internal fun stableReviewRowKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
