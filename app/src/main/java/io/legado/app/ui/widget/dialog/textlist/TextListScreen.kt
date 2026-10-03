package io.legado.app.ui.widget.dialog.textlist

import android.util.Patterns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun TextListScreen(state: TextListUiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            LegadoTopAppBar(state.title, onClose, windowInsets = WindowInsets(0, 0, 0, 0))
            LazyColumn(
                Modifier.weight(1f).testTag("text-list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    val color = MaterialTheme.colorScheme.primary
                    val message =
                        remember(entry.text, color) {
                            buildAnnotatedString {
                                append(entry.text)
                                val matcher = Patterns.WEB_URL.matcher(entry.text)
                                while (matcher.find()) {
                                    // Linkify.WEB_URLS excludes an email address's domain.
                                    if (
                                        matcher.start() > 0 &&
                                            entry.text[matcher.start() - 1] == '@'
                                    )
                                        continue
                                    val value = matcher.group().orEmpty()
                                    addLink(
                                        LinkAnnotation.Url(
                                            if (value.contains("://")) value else "http://$value",
                                            TextLinkStyles(SpanStyle(color = color)),
                                        ),
                                        matcher.start(),
                                        matcher.end(),
                                    )
                                }
                            }
                        }
                    SelectionContainer(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("text-list-row-${entry.id}")
                    ) {
                        Text(
                            message,
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
