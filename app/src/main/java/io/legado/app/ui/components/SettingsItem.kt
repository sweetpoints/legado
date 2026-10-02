package io.legado.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.LocalLegadoColors

/**
 * 单行偏好。[view_preference.xml](../res/layout/view_preference.xml) 的等价实现：
 * 左右 16dp / 上下 10dp、最小高度 60dp、标题 16sp、摘要 14sp 且上边距 8dp。
 */
@Composable
fun SettingsRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLegadoColors.current
    Column(
        modifier = modifier
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
fun SettingsCategoryHeader(title: String, modifier: Modifier = Modifier) {
    val colors = LocalLegadoColors.current
    Column(modifier = modifier.fillMaxWidth()) {
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
