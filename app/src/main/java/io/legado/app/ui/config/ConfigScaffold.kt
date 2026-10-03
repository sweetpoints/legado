package io.legado.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.legado.app.R

/** Shared settings chrome. Query, selection and persistence belong to the host state owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigScaffold(
    title: String,
    searching: Boolean,
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    onSearching: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val submit = {
        if (enabled) query.text.trim().takeIf { it.isNotEmpty() }?.let {
            keyboard?.hide()
            onSearch(it)
        }
        Unit
    }
    BackHandler(searching) { keyboard?.hide(); onSearching(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        enabled = enabled,
                        placeholder = { Text(stringResource(R.string.search)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("config-search-input"),
                    ) else Text(title, Modifier.testTag("config-title"))
                },
                navigationIcon = {
                    IconButton(onClick = { if (searching) { keyboard?.hide(); onSearching(false) } else onBack() },
                        modifier = Modifier.testTag("config-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (searching) IconButton(onClick = submit, enabled = enabled, modifier = Modifier.testTag("config-submit-search")) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                    } else IconButton(onClick = { onSearching(true) }, enabled = enabled, modifier = Modifier.testTag("config-open-search")) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag("config-page")) { content() }
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
}
