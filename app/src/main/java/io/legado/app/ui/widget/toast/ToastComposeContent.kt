package io.legado.app.ui.widget.toast

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.utils.ToastMessage

@Composable
internal fun ToastComposeContent(
    message: ToastMessage,
    backgroundColor: Int,
    textColor: Int,
) {
    val density = LocalDensity.current
    val inlineContent =
        remember(message.inlineImages, density) {
            message.inlineImages.mapValues { (_, image) ->
                InlineTextContent(
                    placeholder =
                        Placeholder(
                            width = with(density) { image.widthPx.toDp() },
                            height = with(density) { image.heightPx.toDp() },
                            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                        )
                ) {
                    Image(
                        bitmap = image.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

    Box(Modifier.fillMaxSize().testTag("compose-toast-root")) {
        Surface(
            modifier =
                Modifier.align(Alignment.TopStart).padding(2.dp).testTag("compose-toast-card"),
            color = Color(backgroundColor),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 2.dp,
        ) {
            Text(
                text = message.annotatedText,
                inlineContent = inlineContent,
                color = Color(textColor),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 10.dp)
                        .testTag("compose-toast-message"),
            )
        }
    }
}
