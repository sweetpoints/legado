package io.legado.app.ui.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.utils.getCompatDrawable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.model.theme.*
import kotlinx.coroutines.launch

internal data class ThemeSettingsActions(val popup: (ThemeSettingsPopup, ThemeColor?) -> Unit,
    val boolean: (ThemeSwitch, Boolean) -> Unit, val destination: (ThemeSettingsDestination) -> Unit,
    val toggleNight: () -> Unit, val retry: () -> Unit, val dismiss: () -> Unit, val number: (Int) -> Unit,
    val color: (Int) -> Unit, val name: (String) -> Unit, val confirm: (Boolean) -> Unit, val launcher: (String) -> Unit,
    val removeImage: (Boolean) -> Unit)
internal data class ThemeSettingsRow(val key: String, val title: String, val summary: String = "", val category: String = "",
    val switch: ThemeSwitch? = null, val color: ThemeColor? = null, val popup: ThemeSettingsPopup? = null,
    val destination: ThemeSettingsDestination? = null)

@Composable internal fun themeSettingsRows(settings: ThemeSettingsSnapshot): List<ThemeSettingsRow> {
    val rows = mutableListOf<ThemeSettingsRow>()
    @Composable fun row(key: String, title: Int, summary: Int? = null, popup: ThemeSettingsPopup? = null, destination: ThemeSettingsDestination? = null) {
        rows += ThemeSettingsRow(key, stringResource(title), summary?.let { stringResource(it) }.orEmpty(), popup = popup, destination = destination)
    }
    if (settings.launcherAvailable) row("launcher", R.string.change_icon, R.string.change_icon_summary, ThemeSettingsPopup.Launcher)
    row("welcome", R.string.welcome_style, R.string.welcome_style_summary, destination = ThemeSettingsDestination.Welcome)
    val switches = listOf(Triple(ThemeSwitch.StatusBar, R.string.immersion_status_bar, R.string.status_bar_immersion),
        Triple(ThemeSwitch.NavigationBar, R.string.imm_navigation_bar, R.string.imm_navigation_bar_s),
        Triple(ThemeSwitch.PredictiveBack, R.string.disable_predictive_back, R.string.disable_predictive_back_summary),
        Triple(ThemeSwitch.WallpaperFollow, R.string.wallpaper_color_follow, R.string.wallpaper_color_follow_summary),
        Triple(ThemeSwitch.WallpaperAuto, R.string.wallpaper_color_auto_update, R.string.wallpaper_color_auto_update_summary))
    @Composable fun addSwitch(index: Int) { val (key, title, summary) = switches[index]
        rows += ThemeSettingsRow(key.key, stringResource(title), stringResource(summary), switch = key) }
    addSwitch(0); addSwitch(1)
    rows += ThemeSettingsRow("elevation", stringResource(R.string.bar_elevation), stringResource(R.string.bar_elevation_s, settings.elevation.toString()), popup = ThemeSettingsPopup.Elevation)
    rows += ThemeSettingsRow("font", stringResource(R.string.font_scale), stringResource(R.string.font_scale_summary, settings.effectiveFontScale), popup = ThemeSettingsPopup.Font)
    row("cover", R.string.cover_config, R.string.cover_config_summary, destination = ThemeSettingsDestination.Cover)
    row("themes", R.string.theme_list, R.string.theme_list_summary, destination = ThemeSettingsDestination.ThemeList)
    row("bottomSkin", R.string.bottom_bar_skin, R.string.bottom_bar_skin_summary, destination = ThemeSettingsDestination.BottomSkin)
    addSwitch(2); if (settings.wallpaperAvailable) { addSwitch(3); addSwitch(4) }
    listOf(false, true).forEach { night ->
        val category = stringResource(if (night) R.string.night else R.string.day)
        rows += ThemeSettingsRow(if (night) "night-category" else "day-category", category, category = category)
        val colors = if (night) listOf(ThemeColor.NightPrimary, ThemeColor.NightAccent, ThemeColor.NightBackground, ThemeColor.NightBottom)
            else listOf(ThemeColor.DayPrimary, ThemeColor.DayAccent, ThemeColor.DayBackground, ThemeColor.DayBottom)
        val titles = listOf(R.string.primary, R.string.accent, R.string.background_color, R.string.navbar_color)
        val summaries = if (night) listOf(R.string.night_primary, R.string.night_accent, R.string.night_background_color, R.string.night_navbar_color)
            else listOf(R.string.day_color_primary, R.string.day_color_accent, R.string.day_background_color, R.string.day_navbar_color)
        colors.forEachIndexed { index, color -> rows += ThemeSettingsRow(color.key, stringResource(titles[index]), stringResource(summaries[index]), category,
            color = color, popup = ThemeSettingsPopup.Color) }
        rows += ThemeSettingsRow(if (night) "night-image" else "day-image", stringResource(R.string.background_image),
            settings.image(night).takeIf { it.isNotBlank() } ?: stringResource(R.string.select_image), category,
            popup = if (night) ThemeSettingsPopup.NightBackground else ThemeSettingsPopup.DayBackground)
        val key = if (night) ThemeSwitch.NightNavigation else ThemeSwitch.DayNavigation
        rows += ThemeSettingsRow(key.key, stringResource(R.string.immersion_nav_bar), stringResource(if (night) R.string.night_nav_bar_immersion else R.string.day_nav_bar_immersion), category, switch = key)
        rows += ThemeSettingsRow(if (night) "save-night" else "save-day", stringResource(R.string.save_theme_config), stringResource(if (night) R.string.save_night_theme_summary else R.string.save_day_theme_summary),
            category, popup = if (night) ThemeSettingsPopup.SaveNight else ThemeSettingsPopup.SaveDay)
    }
    return rows
}

@Composable internal fun ThemeSettingsScreen(state: ThemeSettingsState, actions: ThemeSettingsActions, modifier: Modifier = Modifier,
    search: String? = null, searchFinished: () -> Unit = {}, searchEmpty: () -> Unit = {}) {
    val settings = state.settings; val enabled = !state.loading && !state.failed && !state.busy
    val rows = settings?.let { themeSettingsRows(it) }.orEmpty()
    val scroll = rememberLazyListState(); val scope = rememberCoroutineScope()
    val found = search?.let { query -> rows.filter { !it.key.endsWith("-category") && configPreferenceMatches(query, it.title, it.summary, listOf(it.category)) } }.orEmpty()
    LaunchedEffect(search, state.loading, state.failed) { if (search != null && !state.loading && found.isEmpty()) searchEmpty() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.loading || state.busy) CircularProgressIndicator(Modifier.size(24.dp).testTag("theme-settings-progress"), strokeWidth = 2.dp)
                IconButton(actions.toggleNight, Modifier.size(48.dp).testTag("theme-settings-mode"), enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_daytime), stringResource(R.string.theme_mode))
                }
            }
            state.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(themeProblemText(state.problem) ?: error, Modifier.weight(1f).testTag("theme-settings-error"), color = MaterialTheme.colorScheme.error)
                if (state.failed) TextButton(actions.retry, Modifier.heightIn(min = 48.dp).testTag("theme-settings-retry")) { Text(stringResource(R.string.retry)) }
            } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("theme-settings-list"), state = scroll) {
                items(rows, key = { it.key }) { row ->
                    if (row.key.endsWith("-category")) {
                        HorizontalDivider(); Text(row.title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("theme-row-${row.key}"), color = MaterialTheme.colorScheme.primary)
                    } else {
                        val allowed = enabled && (row.switch != ThemeSwitch.WallpaperAuto || settings?.switches?.get(ThemeSwitch.WallpaperFollow) == true)
                        val checked = row.switch?.let { settings?.switches?.get(it) } == true
                        val clickable = if (row.switch != null) Modifier.toggleable(checked, enabled = allowed, role = Role.Switch) { actions.boolean(row.switch, it) }
                            else Modifier.clickable(enabled = allowed) { row.popup?.let { actions.popup(it, row.color) }; row.destination?.let(actions.destination) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("theme-row-${row.key}").then(clickable).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(row.title); if (row.summary.isNotEmpty()) Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (row.switch != null) Switch(checked, null, enabled = allowed)
                            row.color?.let { Box(Modifier.padding(start = 12.dp).size(28.dp).background(Color(settings?.colors?.get(it) ?: 0))) }
                            if (row.key == "launcher") ThemeLauncherIcon(settings?.launcher.orEmpty())
                        }
                    }
                }
            }
        }
    }
    if (search != null && found.isNotEmpty()) AlertDialog(onDismissRequest = searchFinished, title = { Text(stringResource(R.string.search)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { found.forEach { row ->
            TextButton({ searchFinished(); scope.launch { scroll.animateScrollToItem(rows.indexOfFirst { it.key == row.key }) } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-search-${row.key}")) {
                Text(listOf(row.category, row.title).filter { it.isNotBlank() }.joinToString(" > "))
            }
        } }
    }, confirmButton = {})
    when (state.popup) {
        ThemeSettingsPopup.Font, ThemeSettingsPopup.Elevation -> ThemeNumberPopup(state, actions)
        ThemeSettingsPopup.Color -> ThemeColorPopup(state, actions)
        ThemeSettingsPopup.Launcher -> ThemeLauncherPopup(settings?.launcher.orEmpty(), actions)
        ThemeSettingsPopup.DayBackground, ThemeSettingsPopup.NightBackground -> {
            val night = state.popup == ThemeSettingsPopup.NightBackground
            AlertDialog(onDismissRequest = actions.dismiss, text = { Column {
                TextButton({ actions.destination(if (night) ThemeSettingsDestination.BlurNight else ThemeSettingsDestination.BlurDay) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-image-blur")) { Text(stringResource(R.string.background_image_blurring)) }
                TextButton({ actions.destination(if (night) ThemeSettingsDestination.ImageNight else ThemeSettingsDestination.ImageDay) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-image-select")) { Text(stringResource(R.string.select_image)) }
                if (!settings?.image(night).isNullOrEmpty()) TextButton({ actions.removeImage(night) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-image-delete")) { Text(stringResource(R.string.delete)) }
            } }, confirmButton = {})
        }
        ThemeSettingsPopup.SaveDay, ThemeSettingsPopup.SaveNight -> AlertDialog(onDismissRequest = actions.dismiss,
            title = { Text(stringResource(R.string.theme_name)) }, text = { OutlinedTextField(state.name, actions.name, Modifier.fillMaxWidth().testTag("theme-save-name"), placeholder = { Text("name") }, singleLine = true) },
            confirmButton = { TextButton({ actions.confirm(false) }, Modifier.heightIn(min = 48.dp).testTag("theme-save-confirm"), enabled = enabled) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(actions.dismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
        null -> Unit
    }
}

@Composable private fun themeProblemText(problem: ThemeSettingsProblem?): String? = when (problem) {
    ThemeSettingsProblem.DayTooDark -> stringResource(R.string.day_background_too_dark)
    ThemeSettingsProblem.NightTooLight -> stringResource(R.string.night_background_too_light)
    ThemeSettingsProblem.WallpaperUnavailable -> stringResource(R.string.wallpaper_colors_unavailable)
    null -> null
}
@Composable private fun ThemeNumberPopup(state: ThemeSettingsState, actions: ThemeSettingsActions) {
    val font = state.popup == ThemeSettingsPopup.Font; val range = if (font) 8..16 else 0..32
    var text by rememberSaveable(state.number) { mutableStateOf(state.number.toString()) }
    AlertDialog(onDismissRequest = actions.dismiss, title = { Text(stringResource(if (font) R.string.font_scale else R.string.bar_elevation)) }, text = {
        Column { OutlinedTextField(text, { input -> if (input.isEmpty()) text = input else input.toIntOrNull()?.let { number -> val value = number.coerceIn(range); text = value.toString(); actions.number(value) } },
            Modifier.fillMaxWidth().testTag("theme-number-input"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Slider(state.number.toFloat(), { actions.number(it.toInt()) }, Modifier.testTag("theme-number-slider"), valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
            TextButton({ actions.confirm(true) }, Modifier.heightIn(min = 48.dp).testTag("theme-number-default")) { Text(stringResource(R.string.btn_default_s)) }
        }
    }, confirmButton = { TextButton({ actions.confirm(false) }, Modifier.heightIn(min = 48.dp).testTag("theme-number-confirm"), enabled = text.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(actions.dismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
@Composable private fun ThemeLauncherPopup(current: String, actions: ThemeSettingsActions) {
    val values = stringArrayResource(R.array.icons); val labels = stringArrayResource(R.array.icon_names)
    AlertDialog(onDismissRequest = actions.dismiss, title = { Text(stringResource(R.string.change_icon)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { values.forEachIndexed { index, value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-launcher-$value").clickable { actions.launcher(value) }, verticalAlignment = Alignment.CenterVertically) {
                ThemeLauncherIcon(value)
                Text(labels.getOrElse(index) { value }, Modifier.weight(1f)); RadioButton(value == current, null)
            }
        } }
    }, confirmButton = {})
}
/** Adaptive launcher drawables cannot be loaded by painterResource; draw only this small icon into a bitmap. */
@Composable private fun ThemeLauncherIcon(value: String) {
    val context = LocalContext.current
    val bitmap = remember(context, value) { runCatching {
        val resource = context.resources.getIdentifier(value, "mipmap", context.packageName)
        val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (resource == 0) null else context.getCompatDrawable(resource)?.toBitmap(size, size)?.asImageBitmap()
    }.getOrNull() }
    bitmap?.let { Image(it, null, Modifier.size(40.dp).padding(4.dp)) }
}
@Composable private fun ThemeColorPopup(state: ThemeSettingsState, actions: ThemeSettingsActions) {
    var hex by rememberSaveable(state.colorKey, state.color) { mutableStateOf("#%06X".format(state.color and 0xffffff)) }
    var shadeBase by rememberSaveable(state.colorKey) { mutableIntStateOf(state.color) }
    val initial by rememberSaveable(state.colorKey) { mutableIntStateOf(state.color) }
    val parsed = hex.removePrefix("#").takeIf { it.length == 6 && it.all { char -> char in "0123456789ABCDEFabcdef" } }?.toLongOrNull(16)
    val height = (LocalConfiguration.current.screenHeightDp * .6f).dp.coerceAtMost(440.dp)
    AlertDialog(onDismissRequest = actions.dismiss, title = { Text(stringResource(com.jaredrummler.android.colorpicker.R.string.cpv_default_title)) }, text = {
        Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(Color(state.color)).testTag("theme-color-preview"))
            (listOf(state.color, initial) + ColorPickerDialog.MATERIAL_COLORS.toList() + listOf(0xff000000.toInt())).distinct().chunked(4).forEachIndexed { rowIndex, row -> Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, value -> Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme-color-preset-${rowIndex * 4 + index}").clickable { if (state.color == value) actions.confirm(false) else { shadeBase = value; actions.color(value) } }.padding(4.dp).background(Color(value))) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            } }
            themeColorShades(shadeBase).chunked(4).forEachIndexed { rowIndex, row -> Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, value -> Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme-color-shade-${rowIndex * 4 + index}")
                    .clickable { if (state.color == value) actions.confirm(false) else actions.color(value) }.padding(4.dp).background(Color(value))) }
            } }
            OutlinedTextField(hex, { input -> if (input.length <= 7) hex = input; input.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { actions.color(it.toInt()) } }, Modifier.fillMaxWidth().testTag("theme-color-hex"), label = { Text("#RRGGBB") }, singleLine = true, isError = parsed == null)
            listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) -> Text("$label: ${state.color ushr shift and 255}")
                Slider((state.color ushr shift and 255).toFloat(), { value -> actions.color((state.color and (255 shl shift).inv()) or (value.toInt().coerceIn(0, 255) shl shift)) }, Modifier.testTag("theme-color-$label"), valueRange = 0f..255f) }
            themeProblemText(state.problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("theme-color-validation")) }
        }
    }, confirmButton = { TextButton({ actions.confirm(false) }, Modifier.heightIn(min = 48.dp).testTag("theme-color-confirm"), enabled = parsed != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(actions.dismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
