package io.legado.app.ui.book.read

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R

@Composable
internal fun ReaderSearchMenuRoute(
    controller: ReaderSearchMenuController,
    background: Color,
    foreground: Color,
    settled: (Boolean, Long) -> Unit,
    close: () -> Unit,
    navigate: (Int) -> Unit,
    results: () -> Unit,
    main: () -> Unit,
    exit: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    var completion by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val done by rememberUpdatedState(settled)
    LaunchedEffect(completion, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED)
            completion?.let {
                completion = null
                done(it.first, it.second)
            }
    }
    ReaderSearchMenuScreen(
        state = state,
        background = background,
        foreground = foreground,
        settled = { visible, id -> completion = visible to id },
        close = close,
        navigate = navigate,
        results = results,
        main = main,
        exit = exit,
    )
}

@Composable
internal fun ReaderSearchMenuScreen(
    state: ReaderSearchMenuState,
    background: Color,
    foreground: Color,
    settled: (Boolean, Long) -> Unit,
    close: () -> Unit,
    navigate: (Int) -> Unit,
    results: () -> Unit,
    main: () -> Unit,
    exit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = remember { MutableTransitionState(false) }
    val done by rememberUpdatedState(settled)
    LaunchedEffect(state.visible) { transition.targetState = state.visible }
    LaunchedEffect(transition.isIdle, transition.currentState, state.visible, state.exitId) {
        if (
            transition.isIdle &&
                transition.currentState == state.visible &&
                (state.visible || state.exitId > 0)
        )
            done(state.visible, state.exitId)
    }
    val hasResults = state.results.isNotEmpty()
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = foreground)
    Box(modifier.fillMaxSize()) {
        if (transition.currentState || transition.targetState)
            Box(Modifier.fillMaxSize().testTag("reader-search-dismiss").clickable(onClick = close))
        if (state.navigationVisible && hasResults) {
            FloatingActionButton(
                onClick = { navigate(-1) },
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .testTag("reader-search-left"),
                containerColor = background,
                contentColor = foreground,
            ) {
                Icon(
                    painterResource(R.drawable.ic_arrow_right),
                    stringResource(R.string.help_search_prev),
                    Modifier.rotate(180f),
                )
            }
            FloatingActionButton(
                onClick = { navigate(1) },
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .testTag("reader-search-right"),
                containerColor = background,
                contentColor = foreground,
            ) {
                Icon(
                    painterResource(R.drawable.ic_arrow_right),
                    stringResource(R.string.help_search_next),
                )
            }
        }
        AnimatedVisibility(
            visibleState = transition,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(150)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            Surface(
                color = background,
                contentColor = foreground,
                modifier = Modifier.fillMaxWidth().testTag("reader-search-panel"),
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { navigate(-1) },
                            enabled = hasResults,
                            modifier = Modifier.testTag("reader-search-previous"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_drop_up),
                                stringResource(R.string.help_search_prev),
                            )
                        }
                        IconButton(
                            onClick = { navigate(1) },
                            enabled = hasResults,
                            modifier = Modifier.testTag("reader-search-next"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_drop_down),
                                stringResource(R.string.help_search_next),
                            )
                        }
                        Text(
                            "${stringResource(R.string.search_content_size)}: ${state.results.size} / ${state.chapterTitle}",
                            Modifier.weight(1f).padding(end = 12.dp).testTag("reader-search-info"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(
                            onClick = results,
                            modifier =
                                Modifier.heightIn(min = 48.dp).testTag("reader-search-results"),
                            colors = buttonColors,
                        ) {
                            Text(stringResource(R.string.search_content_size))
                        }
                        TextButton(
                            onClick = main,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("reader-search-main"),
                            colors = buttonColors,
                        ) {
                            Text(stringResource(R.string.main_menu))
                        }
                        TextButton(
                            onClick = exit,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("reader-search-exit"),
                            colors = buttonColors,
                        ) {
                            Text(stringResource(R.string.exit))
                        }
                    }
                }
            }
        }
    }
}
