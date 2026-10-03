package io.legado.app.ui.components.cover

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.data.repository.CoverRepository
import io.legado.app.data.repository.CoverRequest

sealed interface CoverGroupContent {
    data class Single(val request: CoverRequest) : CoverGroupContent

    data class Grid(val previews: List<CoverRequest?>) : CoverGroupContent
}

fun createCoverGroupContent(customCover: String?, books: List<BookshelfBook>): CoverGroupContent =
    if (!customCover.isNullOrBlank() || books.isEmpty())
        CoverGroupContent.Single(CoverRequest(customCover))
    else
        CoverGroupContent.Grid(
            List(4) { index -> books.getOrNull(index)?.let { CoverRequest.from(it) } }
        )

/** Custom cover or a fixed four-slot grid; missing books retain a transparent empty slot. */
@Composable
fun ComposeGroupCover(
    customCover: String?,
    books: List<BookshelfBook>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    repository: CoverRepository? = null,
) {
    val descriptionModifier =
        if (contentDescription == null) Modifier
        else Modifier.semantics { this.contentDescription = contentDescription }
    Box(modifier.aspectRatio(3f / 4f).then(descriptionModifier)) {
        when (val content = createCoverGroupContent(customCover, books)) {
            is CoverGroupContent.Single ->
                ComposeCover(content.request, Modifier.fillMaxSize(), null, repository = repository)
            is CoverGroupContent.Grid ->
                Column(Modifier.fillMaxSize()) {
                    repeat(2) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            repeat(2) { column ->
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    content.previews[row * 2 + column]?.let { request ->
                                        ComposeCover(
                                            request,
                                            Modifier.fillMaxSize(),
                                            null,
                                            repository = repository,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}
