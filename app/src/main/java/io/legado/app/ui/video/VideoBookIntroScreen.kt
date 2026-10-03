package io.legado.app.ui.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.repository.BookDetailIntroImageRepository
import io.legado.app.ui.book.info.detail.BookDetailIntroDocument
import io.legado.app.ui.book.info.detail.BookDetailIntroMode
import io.legado.app.ui.book.info.detail.BookDetailIntroWebContent
import io.legado.app.ui.book.info.detail.bookDetailIntroDocument
import io.legado.app.ui.dict.DictionaryResultAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class VideoBookIntroState(
    val rawIntro: String = "",
    val bookUrl: String = "",
    val source: BaseSource? = null,
)

@Composable
internal fun VideoBookIntroScreen(
    state: VideoBookIntroState,
    onAction: (DictionaryResultAction) -> Unit,
    onLink: (String) -> Unit,
    onImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRepository = remember(context) { BookDetailIntroImageRepository(context) }
    val documentState =
        produceState<BookDetailIntroDocument?>(initialValue = null, state.rawIntro) {
            value = withContext(Dispatchers.IO) { bookDetailIntroDocument(state.rawIntro) }
        }
    val document = documentState.value ?: return
    if (document.content.isBlank()) return

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (document.mode == BookDetailIntroMode.Plain) {
            SelectionContainer {
                Text(
                    text = checkNotNull(document.plain),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                            .testTag("video-book-intro-text"),
                    color = colorResource(R.color.secondaryText),
                    fontSize = 14.sp,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                )
            }
        } else {
            BookDetailIntroWebContent(
                document = document,
                bookUrl = state.bookUrl,
                source = state.source,
                expanded = true,
                image = { imageRepository.image(it, state.source?.getKey()) },
                onAction = onAction,
                onLink = onLink,
                onImage = onImage,
                onOverflow = {},
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}
