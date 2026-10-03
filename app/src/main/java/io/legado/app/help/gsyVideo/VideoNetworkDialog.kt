package io.legado.app.help.gsyVideo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme

/** A single lifecycle-owned native window; the confirmation UI itself is entirely Compose. */
internal class VideoNetworkDialog(context: Context, private val onConfirm: () -> Unit) :
    ComponentDialog(context, R.style.dialog_style) {
    private var accepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        VideoNetworkScreen(
                            onConfirm = {
                                if (!accepted && isShowing) {
                                    accepted = true
                                    dismiss()
                                    onConfirm()
                                }
                            },
                            onCancel = { cancel() },
                        )
                    }
                }
            }
        )
    }
}

@Composable
internal fun VideoNetworkScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(com.shuyu.gsyvideoplayer.R.string.tips_not_wifi))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, Modifier.testTag("video-network-cancel")) {
                    Text(stringResource(com.shuyu.gsyvideoplayer.R.string.tips_not_wifi_cancel))
                }
                TextButton(onConfirm, Modifier.testTag("video-network-confirm")) {
                    Text(stringResource(com.shuyu.gsyvideoplayer.R.string.tips_not_wifi_confirm))
                }
            }
        }
    }
}
