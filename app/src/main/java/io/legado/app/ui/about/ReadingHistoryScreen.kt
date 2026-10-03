package io.legado.app.ui.about

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.image.CoverImage
import io.legado.app.data.repository.*
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import io.legado.app.ui.theme.LocalLegadoColors
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReadingHistoryActions(val query: (String) -> Unit, val preference: (ReadingHistoryPreference, ReadingHistoryPreferences) -> Unit,
    val open: (ReadingHistoryIdentity) -> Unit, val delete: (ReadingHistoryIdentity) -> Unit, val clear: () -> Unit,
    val confirm: () -> Unit, val cancel: () -> Unit, val chooseAuthor: () -> Unit, val removeAuthor: (String) -> Unit,
    val retry: () -> Unit, val dismissError: () -> Unit, val back: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReadingHistoryScreen(state: ReadingHistoryState, actions: ReadingHistoryActions,
    covers: ReadingHistoryCoverRepository, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    var menu by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val prefs = state.preferences
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(containerColor = Color.Transparent, topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.read_record)) }, navigationIcon = {
                    IconButton(onClick = actions.back, modifier = Modifier.testTag("history-back")) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                }, actions = {
                    Box { IconButton({ sorting = true }, Modifier.testTag("history-sort")) { Icon(painterResource(R.drawable.ic_baseline_sort_24), stringResource(R.string.sort)) }
                        DropdownMenu(sorting, { sorting = false }) {
                            listOf(R.string.sort_by_name, R.string.reading_time_sort, R.string.last_read_time_sort).forEachIndexed { index, label ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { actions.preference(ReadingHistoryPreference.Sort, prefs.copy(sort = index)); sorting = false },
                                    leadingIcon = { RadioButton(prefs.sort == index, null) }, modifier = Modifier.testTag("history-sort-$index"))
                            }
                        }
                    }
                    Box { IconButton({ menu = true }, Modifier.testTag("history-menu")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            val entries = listOf(Triple(ReadingHistoryPreference.Enabled, R.string.enable_record, prefs.enabled), Triple(ReadingHistoryPreference.Simple, R.string.read_record_simple_layout, prefs.simple),
                                Triple(ReadingHistoryPreference.Days, R.string.read_record_use_days, prefs.days), Triple(ReadingHistoryPreference.Seconds, R.string.read_record_show_seconds, prefs.seconds), Triple(ReadingHistoryPreference.Fixed, R.string.read_record_fixed_card, prefs.fixed))
                            entries.forEach { (field, label, checked) -> DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Checkbox(checked, null) }, modifier = Modifier.testTag("history-pref-${field.name}"), onClick = {
                                actions.preference(field, when (field) { ReadingHistoryPreference.Enabled -> prefs.copy(enabled = !checked); ReadingHistoryPreference.Simple -> prefs.copy(simple = !checked); ReadingHistoryPreference.Days -> prefs.copy(days = !checked); ReadingHistoryPreference.Seconds -> prefs.copy(seconds = !checked); ReadingHistoryPreference.Fixed -> prefs.copy(fixed = !checked); else -> prefs }); menu = false
                            }) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.read_record_clear)) }, onClick = { menu = false; actions.clear() }, modifier = Modifier.testTag("history-clear-menu"))
                        }
                    }
                })
                OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("history-search"), enabled = state.ready,
                    singleLine = true, label = { Text(stringResource(R.string.search)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            }
        }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (prefs.simple) Row(Modifier.fillMaxWidth().padding(16.dp).testTag("history-compact-summary"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.all_read_time)); Text(formatDuring(state.snapshot.total, prefs.days, prefs.seconds), Modifier.testTag("history-total")) }
                TextButton(actions.clear, enabled = state.ready && !state.busy, modifier = Modifier.testTag("history-clear")) { Text(stringResource(R.string.read_record_clear)) }
            } else if (prefs.fixed) ReadingHistorySummary(state, covers)
            LazyColumn(Modifier.fillMaxSize().testTag("history-list"), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (!prefs.simple && !prefs.fixed) item("summary") { ReadingHistorySummary(state, covers) }
                if (state.snapshot.rows.isEmpty() && !state.loading) item("empty") { Text(stringResource(R.string.read_record_empty), Modifier.padding(24.dp).testTag("history-empty")) }
                items(state.snapshot.rows, key = { it.key }, contentType = { prefs.simple }) { row -> ReadingHistoryRow(row, prefs, covers, actions, state.ready && !state.busy) }
            }
        } }
        state.confirmation?.let { confirmation ->
            val row = state.snapshot.rows.find { it.identity == confirmation.identity }
            AlertDialog(onDismissRequest = { if (!state.busy) actions.cancel() }, title = { Text(stringResource(R.string.delete)) }, text = {
                if (confirmation.chooseAuthor) Column(Modifier.verticalScroll(rememberScrollState())) { row?.legacyAuthors?.forEach { author ->
                    TextButton({ actions.removeAuthor(author) }, Modifier.fillMaxWidth()) { Text(author) }
                } } else Text(if (confirmation.identity == null) stringResource(R.string.read_record_clear)
                    else if (confirmation.author != null) stringResource(R.string.read_record_remove_author_confirm, confirmation.author)
                    else stringResource(R.string.sure_del_any, "${confirmation.identity.name}\n${row?.displayAuthor ?: confirmation.identity.author}"))
            }, confirmButton = { if (!confirmation.chooseAuthor) TextButton(actions.confirm, enabled = !state.busy, modifier = Modifier.testTag("history-confirm")) { Text(stringResource(R.string.ok)) } },
                dismissButton = { Row { if (!confirmation.chooseAuthor && confirmation.author == null && row?.combined == true && row.legacyAuthors.size > 1)
                    TextButton(actions.chooseAuthor, enabled = !state.busy, modifier = Modifier.testTag("history-author-choice")) { Text(stringResource(R.string.read_record_remove_author)) }
                    TextButton(actions.cancel, enabled = !state.busy, modifier = Modifier.testTag("history-cancel")) { Text(stringResource(R.string.cancel)) } } })
        }
        state.error?.let { message -> AlertDialog(onDismissRequest = actions.dismissError, text = { Text(message) }, confirmButton = {
            TextButton({ actions.dismissError(); actions.retry() }) { Text(stringResource(R.string.retry)) }
        }, dismissButton = { TextButton(actions.dismissError) { Text(stringResource(R.string.cancel)) } }) }
    }
}

@Composable private fun ReadingHistorySummary(state: ReadingHistoryState, covers: ReadingHistoryCoverRepository) {
    val colors = LocalLegadoColors.current
    val background = colors.background
    val color = if (colors.isLight) background else Color(ColorUtils.blendColors(background.toArgb(), android.graphics.Color.WHITE, .08f))
    val count = state.snapshot.count.toString()
    val label = stringResource(R.string.read_record_book_count, state.snapshot.count)
    val countText = buildAnnotatedString { append(label); val start = label.indexOf(count); if (start >= 0) addStyle(SpanStyle(color = colors.accent, fontSize = 25.sp), start, start + count.length) }
    Surface(Modifier.fillMaxWidth().padding(16.dp).testTag("history-summary"), color = color, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.read_record_achievement), fontSize = 13.sp, color = colors.textSecondary)
                Text(countText, Modifier.padding(top = 12.dp).testTag("history-count"), fontSize = 18.sp)
                Text(stringResource(R.string.read_record_total_duration, formatDuring(state.snapshot.total, state.preferences.days, state.preferences.seconds)), Modifier.padding(top = 8.dp).testTag("history-total"), fontSize = 13.sp, color = colors.textSecondary)
            }
            Box(Modifier.padding(start = 8.dp).size(112.dp, 108.dp)) {
                listOf(2, 1, 0).forEach { index -> state.snapshot.top.getOrNull(index)?.let { row ->
                    val w = listOf(60.dp, 54.dp, 48.dp)[index]; val x = listOf(46.dp, 28.dp, 4.dp)[index]; val y = listOf(8.dp, 20.dp, 30.dp)[index]
                    ReadingHistoryCover(row, state.preferences.fallback, covers, Modifier.offset(x, y).size(w, w * 4 / 3).rotate(listOf(8f, -3f, -10f)[index]).testTag("history-summary-cover-$index"))
                } }
            }
        }
    }
}
@Composable private fun ReadingHistoryRow(row: ReadingHistoryRow, prefs: ReadingHistoryPreferences, covers: ReadingHistoryCoverRepository, actions: ReadingHistoryActions, enabled: Boolean) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val author = if (row.combined) stringResource(R.string.read_record_legacy_authors, row.displayAuthor) else row.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
    val date = remember(row.lastRead, LocalContext.current.resources.configuration.locales) { if (row.lastRead > 0) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(row.lastRead)) else "" }
    Row(Modifier.fillMaxWidth().clickable(enabled) { actions.open(row.identity) }.padding(16.dp).testTag("history-row-${row.key}"), verticalAlignment = Alignment.Top) {
        if (!prefs.simple) ReadingHistoryCover(row, prefs.fallback, covers, Modifier.padding(end = 12.dp).size(48.dp, 64.dp).testTag("history-cover-${row.key}"))
        Column(Modifier.weight(1f)) {
            Text(row.identity.name, Modifier.testTag("history-title-${row.key}"), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (prefs.simple) stringResource(R.string.author_show, author) else author, Modifier.padding(top = 4.dp).testTag("history-author-${row.key}"), fontSize = 13.sp, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!prefs.simple) Text(row.chapter ?: stringResource(R.string.read_record_no_chapter), Modifier.padding(top = 4.dp).testTag("history-chapter-${row.key}"), fontSize = 13.sp, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatDuring(row.readTime, prefs.days, prefs.seconds), Modifier.padding(top = 4.dp).testTag("history-time-${row.key}"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(date, Modifier.padding(top = 4.dp).testTag("history-date-${row.key}"), fontSize = 12.sp, color = secondary)
        }
        IconButton({ actions.delete(row.identity) }, enabled = enabled, modifier = Modifier.testTag("history-delete-${row.key}")) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete), tint = secondary) }
    }
}

/** Keeps the lease until cancellation and stops drawable callbacks before Glide clears it. */
@Composable private fun ReadingHistoryCover(row: ReadingHistoryRow, fallback: String?, repository: ReadingHistoryCoverRepository, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var result by remember(row.cover, fallback, repository) { mutableStateOf<ReadingHistoryCoverResult?>(null) }
    LaunchedEffect(row.cover, fallback, repository, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        var owned: ReadingHistoryCoverResult? = null
        try { owned = repository.load(row.cover, fallback, size.width, size.height); ensureActive(); result = owned; awaitCancellation() }
        finally { result = null; (owned?.image as? CoverImage.Animated)?.resource?.release() }
    }
    val image = result?.image
    val shape = RoundedCornerShape(4.dp)
    val placeholder = result == null || result?.placeholder == true
    Box(modifier.clip(shape).then(if (placeholder) Modifier.background(Color.White).border(1.dp, Color(0x1A000000), shape) else Modifier).onSizeChanged { size = it }) {
        when (image) {
            is CoverImage.Static -> Image(remember(image.bitmap) { BitmapPainter(image.bitmap.asImageBitmap()) }, row.identity.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            is CoverImage.Animated -> {
                val painter = remember(image.resource) { LifecycleDrawablePainter(image.resource) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(painter, owner) {
                    val lifecycle = owner.lifecycle
                    val observer = LifecycleEventObserver { _, _ -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start() else painter.stop() }
                    lifecycle.addObserver(observer); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start() else painter.stop()
                    onDispose { lifecycle.removeObserver(observer); painter.stop() }
                }
                Image(painter, row.identity.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            null -> Unit
        }
    }
}
