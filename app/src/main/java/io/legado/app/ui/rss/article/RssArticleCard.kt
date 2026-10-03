package io.legado.app.ui.rss.article

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.RssArticleRow

/** Five existing presentations share immutable fields and caller-owned image/navigation slots. */
@Composable
fun RssArticleCard(row: RssArticleRow, style: Int, landscape: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, image: @Composable (Modifier, Boolean, Boolean) -> Unit = { _, _, _ -> }) {
    val titleColor = colorResource(if (row.read) R.color.tv_text_summary else R.color.primaryText)
    val titleModifier = Modifier.testTag("rss-article-title-${row.key}")
    val dateModifier = Modifier.testTag("rss-article-date-${row.key}")
    val root = modifier.fillMaxWidth().testTag("rss-article-row-${row.key}").clickable(onClick = onClick)
    when (style) {
        1 -> Column(root) {
            image(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).height(208.dp), false, false)
            Text(row.title, titleModifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), color = titleColor,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.pubDate.orEmpty(), dateModifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(12.dp)); HorizontalDivider(thickness = 8.dp, color = colorResource(R.color.bg_divider_line))
        }
        2, 4 -> Column(root.padding(horizontal = if (style == 2) 4.dp else 2.dp, vertical = 6.dp)) {
            image(Modifier.fillMaxWidth().padding(top = 2.dp).height(if (style == 2) 272.dp else 182.dp)
                .background(colorResource(R.color.background), RoundedCornerShape(1.dp))
                .border(1.dp, colorResource(R.color.bg_divider_line), RoundedCornerShape(1.dp)), false, style == 2)
            Text(row.title, titleModifier.padding(top = 10.dp), color = titleColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.pubDate.orEmpty(), dateModifier.padding(top = 8.dp, bottom = 2.dp), fontSize = 11.sp, maxLines = 1)
        }
        3 -> Surface(root, shape = RoundedCornerShape(12.dp), color = colorResource(R.color.card_bg_water),
            border = BorderStroke(0.8.dp, colorResource(R.color.card_border_water))) {
            Column {
                image(Modifier.fillMaxWidth(), true, false)
                val horizontal = if (landscape) 8.dp else 6.dp
                Text(row.title, titleModifier.padding(start = horizontal, end = horizontal, top = 10.dp), color = titleColor,
                    fontSize = if (landscape) 16.sp else 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = if (landscape) 5 else 9, overflow = TextOverflow.Ellipsis)
                Text(row.pubDate.orEmpty(), dateModifier.padding(start = horizontal, end = horizontal, top = 8.dp, bottom = 12.dp),
                    fontSize = if (landscape) 14.sp else 11.sp, maxLines = if (landscape) 19 else 39)
            }
        }
        else -> Row(root.height(100.dp).padding(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(row.title, titleModifier.weight(1f), color = titleColor, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.pubDate.orEmpty(), dateModifier.padding(top = 8.dp), fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1)
            }
            image(Modifier.padding(start = 16.dp).size(94.dp, 68.dp), false, false)
        }
    }
}
