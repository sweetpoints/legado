package io.legado.app.ui.qrcode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrScanScreen(
    state: QrScanState,
    cameraAvailable: Boolean,
    onGallery: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    preview: @Composable (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_qr_code)) },
                navigationIcon = {
                    IconButton(onBack, Modifier.testTag("qr-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onGallery,
                        enabled =
                            !state.decoding && state.pendingResult == null && !state.completed,
                        modifier = Modifier.testTag("qr-gallery"),
                    ) {
                        Text(stringResource(R.string.gallery))
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().testTag("qr-preview")) {
            val enabled =
                cameraAvailable &&
                    !state.loading &&
                    !state.decoding &&
                    state.pendingResult == null &&
                    !state.completed
            preview(enabled)
            if (cameraAvailable)
                Canvas(Modifier.fillMaxSize()) {
                    val side = size.minDimension * .8f
                    val left = (size.width - side) / 2
                    val top = (size.height - side) / 2
                    drawRect(
                        Color.White.copy(alpha = .7f),
                        Offset(left, top),
                        androidx.compose.ui.geometry.Size(side, side),
                        style = Stroke(2.dp.toPx()),
                    )
                    drawLine(
                        Color(0xFF4CAF50),
                        Offset(left, top + side / 2),
                        Offset(left + side, top + side / 2),
                        2.dp.toPx(),
                    )
                }
            if (state.loading || state.decoding)
                CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("qr-progress"))
            state.error?.let { message ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    tonalElevation = 3.dp,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, Modifier.testTag("qr-error"))
                        TextButton(onRetry, Modifier.testTag("qr-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
