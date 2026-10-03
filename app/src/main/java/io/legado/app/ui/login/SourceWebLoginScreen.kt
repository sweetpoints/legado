package io.legado.app.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWebLoginScreen(title: String, state: SourceWebLoginState, active: Boolean,
    snackbar: SnackbarHostState, back: () -> Unit, check: () -> Unit,
    browser: @Composable BoxScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag("source-web-login-root")) {
        Scaffold(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Text(stringResource(R.string.login_source, title)) }, navigationIcon = {
                IconButton(back, enabled = active, modifier = Modifier.testTag("source-web-login-back")) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
            }, actions = {
                IconButton(check, enabled = active && !state.checking, modifier = Modifier.testTag("source-web-login-check")) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.ok)) }
            }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).testTag("source-web-login-content")) {
                browser()
                if (state.progress != 100) LinearProgressIndicator(progress = { state.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(1.dp).testTag("source-web-login-progress"))
            }
        }
    }
}
