package io.legado.app.ui.code

import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter.ErrorReason
import kotlinx.coroutines.CancellationException

internal fun curlNoticeText(context: Context, effect: CurlEffect, detail: String): String =
    curlNoticeText(context.resources, effect, detail)

private fun curlNoticeText(resources: Resources, effect: CurlEffect, detail: String): String =
    when (effect.notice) {
        CurlNotice.NoOutput -> resources.getString(R.string.curl_converter_no_output)
        CurlNotice.InsertFailed -> resources.getString(R.string.curl_converter_insert_failed)
        CurlNotice.Conversion ->
            when (effect.reason) {
                ErrorReason.EMPTY_INPUT -> resources.getString(R.string.curl_converter_empty_input)
                ErrorReason.INVALID_CURL -> resources.getString(R.string.curl_converter_invalid_curl)
                ErrorReason.MISSING_URL -> resources.getString(R.string.curl_converter_missing_url)
                ErrorReason.INVALID_ANALYZE_URL ->
                    resources.getString(R.string.curl_converter_invalid_analyze_url)
                ErrorReason.UNSUPPORTED_METHOD ->
                    resources.getString(R.string.curl_converter_unsupported_method, detail)
                ErrorReason.UNSUPPORTED_OPTION ->
                    resources.getString(R.string.curl_converter_unsupported_option, detail)
                null -> resources.getString(R.string.curl_converter_failed)
            }
        else -> resources.getString(R.string.curl_converter_failed)
    }

@Composable
internal fun CurlConversionRoute(
    viewModel: CurlConversionViewModel,
    canHandle: () -> Boolean,
    onCopy: (String) -> Unit,
    onInsert: (String, (Boolean) -> Unit) -> Unit,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resources by rememberUpdatedState(LocalResources.current)
    val ready by rememberUpdatedState(canHandle)
    val copy by rememberUpdatedState(onCopy)
    val insert by rememberUpdatedState(onInsert)
    val toast by rememberUpdatedState(onToast)
    val close by rememberUpdatedState(onClose)
    BackHandler { viewModel.finish() }
    LaunchedEffect(viewModel, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (!ready()) return@collect
                if (value.finished) {
                    if (!closed) {
                        closed = true
                        close()
                    }
                    return@collect
                }
                if (value.loading || value.loadFailed) return@collect
                val effect = value.effects.firstOrNull() ?: return@collect
                fun deliverable() =
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        ready() &&
                        !viewModel.state.value.finished &&
                        viewModel.state.value.effects.any { it.id == effect.id }
                try {
                    val needsPayload =
                        effect.action != CurlAction.Toast || effect.notice == CurlNotice.Conversion
                    val text = if (needsPayload) viewModel.effectText(effect) else ""
                    if (!deliverable()) return@collect
                    viewModel.consume(effect)
                    when (effect.action) {
                        CurlAction.Copy ->
                            if (text.isNotEmpty()) copy(text)
                            else toast(resources.getString(R.string.curl_converter_no_output))
                        CurlAction.Insert ->
                            if (text.isNotEmpty()) {
                                try {
                                    insert(text) { success ->
                                        viewModel.insertionResult(effect.id, success)
                                    }
                                } catch (_: Exception) {
                                    viewModel.insertionResult(effect.id, false)
                                }
                            } else viewModel.insertionResult(effect.id, false)
                        CurlAction.Toast -> toast(curlNoticeText(resources, effect, text))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (deliverable()) {
                        viewModel.consume(effect)
                        if (effect.action == CurlAction.Insert)
                            viewModel.insertionResult(effect.id, false)
                        else toast(resources.getString(R.string.curl_converter_failed))
                    }
                }
            }
        }
    }
    CurlConversionScreen(
        state,
        viewModel.canInsert,
        viewModel::input,
        viewModel::direction,
        viewModel::convert,
        viewModel::copy,
        viewModel::insert,
        viewModel::finish,
        viewModel::retryLoad,
        modifier,
    )
}
