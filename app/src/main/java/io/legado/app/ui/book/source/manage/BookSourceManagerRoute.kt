package io.legado.app.ui.book.source.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx

@Composable
internal fun BookSourceManagerRoute(
    model: BookSourceManagerViewModel,
    onEffect: (PreparedSourceManagerEffect) -> Unit,
    canHandleEffect: () -> Boolean,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val effectHandler by rememberUpdatedState(onEffect)
    val ready by rememberUpdatedState(canHandleEffect)
    LaunchedEffect(model, lifecycleOwner) {
        dispatchSourceManagerEffects(
            model,
            lifecycleOwner.lifecycle,
            { ready() },
            { effectHandler(it) },
        )
    }

    val actions =
        remember(model) {
            SourceManagerActions(
                query = model::query,
                toggle = model::toggle,
                selectAll = model::selectAll,
                invert = model::invert,
                action = { action, key ->
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
                },
                mutation = { action, key ->
                    when (action) {
                        SourceMutation.ADD_GROUP -> model.open(SourceManagerDialog.ADD_GROUP, key)
                        SourceMutation.REMOVE_GROUP ->
                            model.open(SourceManagerDialog.REMOVE_GROUP, key)
                        else ->
                            if (key == null) model.mutate(action)
                            else model.mutate(action, listOf(key))
                    }
                },
                sort = model::sort,
                status = model::status,
                draft = model::draft,
                forgetImport = model::forgetImport,
                confirm = model::confirm,
                dismiss = model::dismiss,
                beginDrag = model::beginDrag,
                previewDrag = model::previewDrag,
                finishDrag = model::finishDrag,
                slide = model::slide,
            )
        }
    BookSourceManagerScreen(state, actions)
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
            if (currentState.error != null || currentState.busy) return@collect
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
