package io.legado.app.ui.dict

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import io.legado.app.utils.toastOnUi
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DictionaryLookupRoute(model: DictionaryLookupViewModel, onInvalidWord: () -> Unit, onImage: (String) -> Unit,
    modifier: Modifier = Modifier, onResultReady: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val invalid by rememberUpdatedState(onInvalidWord)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scrollStates = rememberSaveableStateHolder()
    LaunchedEffect(state.invalidWord) { if (state.invalidWord) invalid() }
    DictionaryLookupScreen(state, model::select, model::retry, modifier) { document, resultModifier ->
        val rule = state.rules.firstOrNull { it.name == state.selected }
        if (rule != null) {
            val signature = listOf(state.word, rule.name, rule.urlRule, rule.showRule).joinToString("|") { "${it.length}:$it" }
            scrollStates.SaveableStateProvider(signature) {
                var scrollY by rememberSaveable { mutableIntStateOf(0) }
                key(document) { DictionaryResultWebView(document, model::image, model::action,
                    { url -> try { uriHandler.openUri(url) } catch (error: Exception) { context.toastOnUi(error.localizedMessage ?: "ERROR") } },
                    onImage, resultModifier, onResultReady, scrollY, { scrollY = it }) }
            }
        }
    }
}
