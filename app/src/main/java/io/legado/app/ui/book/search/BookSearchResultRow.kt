package io.legado.app.ui.book.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.CoverRequest
import io.legado.app.model.webBook.BookSearchResult
import io.legado.app.ui.components.cover.ComposeCover

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookSearchResultRow(
    result: BookSearchResult,
    onShelf: Boolean,
    hasRead: Boolean,
    loadOnlyWifi: Boolean,
    onClick: () -> Unit,
    cover: @Composable (Modifier) -> Unit = { modifier ->
        ComposeCover(
            request =
                CoverRequest(
                    result.coverUrl,
                    result.name,
                    result.author,
                    loadOnlyWifi,
                    result.origin,
                ),
            modifier = modifier,
            contentDescription = stringResource(R.string.img_cover),
        )
    },
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 126.dp)
                .clickable(onClick = onClick)
                .padding(8.dp)
                .testTag("search-result-${result.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover(Modifier.width(80.dp).height(110.dp).testTag("search-cover-${result.id}"))
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val marker =
                    when {
                        onShelf -> Color(0xFF43A047)
                        hasRead -> Color(0xFFFB8C00)
                        else -> Color.Transparent
                    }
                Box(
                    Modifier.size(8.dp)
                        .background(marker, CircleShape)
                        .testTag(
                            if (onShelf) "search-shelf-marker-${result.id}"
                            else if (hasRead) "search-read-marker-${result.id}"
                            else "search-empty-marker-${result.id}"
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = result.name,
                    modifier = Modifier.weight(1f).testTag("search-title-${result.id}"),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.origins.isNotEmpty()) {
                    Text(
                        text = result.origins.size.toString(),
                        modifier =
                            Modifier.padding(start = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("search-origin-count-${result.id}"),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 12.sp,
                    )
                }
            }
            Text(
                text = stringResource(R.string.author_show, result.author),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val labels = buildList {
                result.wordCount?.takeIf(String::isNotBlank)?.let(::add)
                result.kind
                    ?.split(',', '\n')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.let(::addAll)
            }
            if (labels.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().testTag("search-labels-${result.id}"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    labels.forEach { label ->
                        Text(
                            text = label,
                            modifier =
                                Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            result.latestChapterTitle?.takeIf(String::isNotEmpty)?.let { latest ->
                Text(
                    text = stringResource(R.string.lasted_show, latest),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val intro = result.intro?.trim().orEmpty()
            Text(
                text =
                    if (intro.isEmpty()) stringResource(R.string.intro_show_null)
                    else stringResource(R.string.intro_show, intro),
                modifier = Modifier.testTag("search-intro-${result.id}"),
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
