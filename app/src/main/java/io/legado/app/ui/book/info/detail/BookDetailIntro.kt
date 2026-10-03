package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.DictionaryImageData

import io.legado.app.ui.dict.DictionaryResultAction

@Composable fun BookDetailIntro(document:BookDetailIntroDocument,bookUrl:String,source:BookSource?,expanded:Boolean,
    onExpanded:(Boolean)->Unit,image:suspend(String)->DictionaryImageData,onAction:(DictionaryResultAction)->Unit,
    onLink:(String)->Unit,onImage:(String)->Unit,modifier:Modifier=Modifier) {
    if(document.content.isBlank())return
    var overflow by remember(document.signature){mutableStateOf(false)}
    Column(modifier.testTag("book-detail-intro")) {
        if(document.mode==BookDetailIntroMode.Plain) {
            val text=checkNotNull(document.plain)
            SelectionContainer {
                Text(text,Modifier.fillMaxWidth().testTag("book-detail-intro-text"),style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(expanded)Int.MAX_VALUE else 4,
                    overflow=TextOverflow.Ellipsis,onTextLayout={overflow=it.lineCount>4 || it.hasVisualOverflow})
            }
        }else BookDetailIntroWebContent(document,bookUrl,source,expanded,image,onAction,onLink,onImage,
            onOverflow={overflow=it},modifier=Modifier.fillMaxWidth().testTag("book-detail-intro-web"))
        if(document.canCollapse && overflow)TextButton({onExpanded(!expanded)},modifier=Modifier.heightIn(min=48.dp).testTag("book-detail-intro-toggle")) {
            Text(stringResource(if(expanded)R.string.book_intro_collapse else R.string.book_intro_expand))
        }
    }
}
