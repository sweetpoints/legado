package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.compose.LocalLegadoColors

/**
 * 「关于」页的 Compose 实现。
 *
 * 偏好列表原先由 `AboutFragment`（PreferenceFragmentCompat + `R.xml.about`）承载，
 * 现已改为纯 Compose 列表，作为「设置类页面」迁移的参考样板。
 * 行的内边距/字号与原先的 `view_preference.xml` 对齐。
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
    val accent = colors.accent
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
                // 原实现用 filletBackground：圆角 3dp + 背景色
                .clip(RoundedCornerShape(3.dp))
                .background(colors.background)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = summaryText,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(aboutMainItems) { item ->
                AboutRow(
                    title = stringResource(item.titleRes),
                    summary = when {
                        item.key == "update_log" -> versionSummary
                        item.summaryRes != null -> stringResource(item.summaryRes)
                        else -> null
                    },
                    onClick = { onItemClick(item.key) },
                )
            }
            item { AboutCategoryHeader(stringResource(R.string.other)) }
            items(aboutOtherItems) { item ->
                AboutRow(
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

private val aboutMainItems = listOf(
    AboutItem("contributors", R.string.contributors, R.string.contributors_summary),
    AboutItem("update_log", R.string.update_log),
    AboutItem("check_update", R.string.check_update),
    AboutItem("check_beta_update", R.string.check_beta_update),
)

private val aboutOtherItems = listOf(
    AboutItem("crashLog", R.string.crash_log),
    AboutItem("saveLog", R.string.save_log),
    AboutItem("createHeapDump", R.string.create_heap_dump),
    AboutItem("privacyPolicy", R.string.privacy_policy),
    AboutItem("license", R.string.license),
    AboutItem("disclaimer", R.string.disclaimer),
)

/**
 * 顶栏。原实现是 `TitleBar` + `R.menu.about`，这里改为纯 Compose。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutTopBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onScoring: () -> Unit,
) {
    val colors = LocalLegadoColors.current
    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.primary,
            titleContentColor = colors.onPrimary,
            navigationIconContentColor = colors.onPrimary,
            actionIconContentColor = colors.onPrimary,
        ),
    )
}

/**
 * 单行偏好。[view_preference.xml](../res/layout/view_preference.xml) 的等价实现：
 * 左右 16dp / 上下 10dp、最小高度 60dp、标题 16sp、摘要 14sp 且上边距 8dp。
 */
@Composable
private fun AboutRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    val colors = LocalLegadoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary,
                fontSize = 14.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 分类标题。[view_preference_category.xml](../res/layout/view_preference_category.xml) 的等价实现：
 * 上方 8dp 间隔 + 上 16dp / 下 8dp / 左 16dp 内边距。
 *
 * 原布局用的是静态 `@color/accent`，这里改用主题强调色（`ThemeStore`）以便跟随用户自定义主题。
 */
@Composable
private fun AboutCategoryHeader(title: String) {
    val colors = LocalLegadoColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = colors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

/**
 * 原文是把 `legado_gzh` 这段文字染成强调色。原实现依赖 View 测量完成后的 `post {}`，
 * 且原文找不到时靠 `runCatching` 兜底；这里显式处理找不到的情况。
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
