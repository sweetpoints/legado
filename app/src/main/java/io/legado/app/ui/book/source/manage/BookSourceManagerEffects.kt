package io.legado.app.ui.book.source.manage

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx

/** The original native action mapping, shared by every presentation of this manager. */
internal fun handleSourceManagerAction(model: BookSourceManagerViewModel, action: String, key: String = "") {
    when (action) {
        "delete" ->
            model.open(SourceManagerDialog.DELETE, key.takeIf(String::isNotEmpty))
        "online" -> model.open(SourceManagerDialog.IMPORT)
        "check" -> model.open(SourceManagerDialog.CHECK)
        "export" -> model.export(false)
        "share" -> model.export(true)
        "interval" -> model.interval()
        "descending" -> model.ascending()
        "domain" -> model.domain()
        "show-status" -> model.showStatus()
        "block-navigation" -> model.blockNavigation()
        "retry" -> model.retry()
        "up" -> model.step(key, -1)
        "down" -> model.step(key, 1)
        "top",
        "bottom" -> {
            val ascending = model.state.value.ascending
            val top = (action == "top") == ascending
            model.mutate(
                if (top) SourceMutation.TOP else SourceMutation.BOTTOM,
                listOf(key),
            )
        }
        "explore" -> {
            model.state.value.rows
                .find { it.url == key }
                ?.let { row ->
                    model.mutate(
                        if (row.exploreEnabled) SourceMutation.DISABLE_EXPLORE
                        else SourceMutation.ENABLE_EXPLORE,
                        listOf(key),
                    )
                }
        }
        "filter-enabled" -> model.query(appCtx.getString(R.string.enabled))
        "filter-disabled" -> model.query(appCtx.getString(R.string.disabled))
        "filter-login" -> model.query(appCtx.getString(R.string.need_login))
        "filter-no-group" -> model.query(appCtx.getString(R.string.no_group))
        "filter-explore" -> model.query(appCtx.getString(R.string.enabled_explore))
        "filter-no-explore" ->
            model.query(appCtx.getString(R.string.disabled_explore))
        else -> model.effect(action, key)
    }
}

internal fun handleSourceManagerMutation(model: BookSourceManagerViewModel, action: SourceMutation, key: String? = null) {
    when (action) {
        SourceMutation.ADD_GROUP -> model.open(SourceManagerDialog.ADD_GROUP, key)
        SourceMutation.REMOVE_GROUP ->
            model.open(SourceManagerDialog.REMOVE_GROUP, key)
        else ->
            if (key == null) model.mutate(action)
            else model.mutate(action, listOf(key))
    }
}

/** Preparation is cancellable; only the durable receipt and synchronous launch share acceptance. */
internal suspend fun dispatchSourceManagerEffects(
    model: BookSourceManagerViewModel,
    lifecycle: Lifecycle,
    canHandleEffect: () -> Boolean,
    onEffect: (PreparedSourceManagerEffect) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        model.state.collect { currentState ->
            if (!canDispatchSourceManagerEffects(currentState)) return@collect
            currentState.effect?.let { effect ->
                var prepared: PreparedSourceManagerEffect? = null
                try {
                    val request = model.prepareEffect(effect)
                    prepared = request
                    currentCoroutineContext().ensureActive()
                    if (
                        model.deliverEffect(
                            effect.id,
                            {
                                lifecycle.currentState == Lifecycle.State.RESUMED &&
                                    canHandleEffect()
                            },
                            { onEffect(request) },
                        )
                    ) {
                        prepared = null
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    model.hostError(failure.localizedMessage ?: "无法打开操作")
                } finally {
                    prepared?.let(model::releasePreparedEffect)
                }
            }
        }
    }
}

/** Error recovery may leave the page, while every other failed operation stays blocked. */
internal fun canDispatchSourceManagerEffects(state: SourceManagerState): Boolean =
    !state.busy && (state.error == null || state.effect?.action == "back")
