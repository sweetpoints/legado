package io.legado.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.LocalLegadoColors

data class WelcomeUiState(val showText: Boolean = true, val showIcon: Boolean = true)

/** Static presentation state; startup timing and Android navigation belong to the host. */
@Composable
fun WelcomeScreen(state: WelcomeUiState, modifier: Modifier = Modifier) {
    val accent = LocalLegadoColors.current.accent
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (state.showText) {
            Row(
                Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.22f),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.width(6.dp).height(120.dp).background(accent))
                Text(
                    stringResource(R.string.welcome_title),
                    Modifier.padding(start = 6.dp), color = accent, fontSize = 49.sp,
                )
                Text(
                    stringResource(R.string.welcome_subtitle),
                    Modifier.padding(start = 6.dp, top = 60.dp), color = accent, fontSize = 16.sp,
                )
            }
            Text(
                stringResource(R.string.welcome_tagline),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                color = accent, fontSize = 16.sp, letterSpacing = 1.6.sp,
            )
        }
        if (state.showIcon) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp)) {
                Icon(
                    painterResource(R.drawable.icon_read_book),
                    contentDescription = stringResource(R.string.welcome),
                    modifier = Modifier.size(120.dp), tint = accent,
                )
            }
        }
    }
}
