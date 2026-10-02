package io.legado.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.theme.LocalLegadoColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegadoTopAppBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    backLabel: String = stringResource(R.string.back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalLegadoColors.current
    TopAppBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), backLabel)
            }
        },
        actions = actions,
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.primary,
            titleContentColor = colors.onPrimary,
            navigationIconContentColor = colors.onPrimary,
            actionIconContentColor = colors.onPrimary,
        ),
    )
}
