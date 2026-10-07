package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar
import io.legado.app.ui.components.SettingsCategoryHeader
import io.legado.app.ui.components.SettingsRow
import io.legado.app.ui.theme.LocalLegadoColors

/**
 * 「关于」页的 Compose 实现。
 *
 * 偏好列表原先由 `AboutFragment`（PreferenceFragmentCompat + `R.xml.about`）承载， 现已改为纯 Compose
 * 列表，作为「设置类页面」迁移的参考样板。 行的内边距/字号与原先的 `view_preference.xml` 对齐。
 */
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onScoring: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLegadoColors.current
    val accent = MaterialTheme.colorScheme.secondary
    val summary = stringResource(R.string.about_description)
    val gzh = stringResource(R.string.legado_gzh)
    // buildAnnotatedString 取代原来的 ForegroundColorSpan + View.post {}
    val summaryText = remember(summary, gzh, accent) { summary.withAccent(gzh, accent) }
    // stringResource 必须在 @Composable 上下文里取值，不能放进 remember 的 lambda
    val versionSummary = "${stringResource(R.string.version)} $version"

    Column(modifier = modifier.fillMaxSize()) {
        AboutTopBar(
            onBack = onBack,
            onShare = onShare,
            onScoring = onScoring,
        )

        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(6.dp)
                    // 原实现用 filletBackground：圆角 3dp + 背景色
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.background)
                    .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                // 显式指定：不依赖 LocalContentColor 的默认值（MaterialTheme 并不设置它）
                color = colors.textPrimary,
            )
            Text(
                text = summaryText,
                modifier = Modifier.fillMaxWidth(),
                color = colors.textPrimary,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(aboutMainItems, key = { it.key }) { item ->
                SettingsRow(
                    title = stringResource(item.titleRes),
                    summary =
                        when {
                            item.key == "update_log" -> versionSummary
                            item.summaryRes != null -> stringResource(item.summaryRes)
                            else -> null
                        },
                    onClick = { onItemClick(item.key) },
                )
            }
            item { SettingsCategoryHeader(stringResource(R.string.other)) }
            items(aboutOtherItems, key = { it.key }) { item ->
                SettingsRow(
                    title = stringResource(item.titleRes),
                    summary = item.summaryRes?.let { stringResource(it) },
                    onClick = { onItemClick(item.key) },
                )
            }
        }
    }
}

/** 偏好项。`key` 同时用于 `AboutActivity` 的点击分发。 */
private data class AboutItem(
    val key: String,
    val titleRes: Int,
    val summaryRes: Int? = null,
)

private val aboutMainItems =
    listOf(
        AboutItem("contributors", R.string.contributors, R.string.contributors_summary),
        AboutItem("update_log", R.string.update_log),
        AboutItem("check_update", R.string.check_update),
        AboutItem("check_beta_update", R.string.check_beta_update),
    )

private val aboutOtherItems =
    listOf(
        AboutItem("crashLog", R.string.crash_log),
        AboutItem("saveLog", R.string.save_log),
        AboutItem("createHeapDump", R.string.create_heap_dump),
        AboutItem("privacyPolicy", R.string.privacy_policy),
        AboutItem("license", R.string.license),
        AboutItem("disclaimer", R.string.disclaimer),
    )

/** 顶栏。原实现是 `TitleBar` + `R.menu.about`，这里改为纯 Compose。 */
@Composable
private fun AboutTopBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onScoring: () -> Unit,
) {
    LegadoTopAppBar(
        title = stringResource(R.string.about),
        onBack = onBack,
        actions = {
            IconButton(onClick = onShare) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.share),
                )
            }
            IconButton(onClick = onScoring) {
                Icon(
                    painter = painterResource(R.drawable.ic_scoring),
                    contentDescription = stringResource(R.string.scoring),
                )
            }
        },
    )
}

/**
 * 原文是把 `legado_gzh` 这段文字染成强调色。原实现依赖 View 测量完成后的 `post {}`， 且原文找不到时靠 `runCatching` 兜底；这里显式处理找不到的情况。
 */
private fun String.withAccent(highlight: String, accent: Color): AnnotatedString {
    val start = indexOf(highlight)
    if (highlight.isEmpty() || start < 0) return AnnotatedString(this)
    return buildAnnotatedString {
        append(substring(0, start))
        withStyle(SpanStyle(color = accent)) { append(highlight) }
        append(substring(start + highlight.length))
    }
}
