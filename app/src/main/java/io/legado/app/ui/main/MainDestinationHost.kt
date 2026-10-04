package io.legado.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.GlideRssArticleImageRepository
import io.legado.app.ui.main.bookshelf.settings.BookshelfAddProgressDialog
import io.legado.app.ui.main.bookshelf.style1.BookshelfHomeRoute
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageParameters
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageRoute
import io.legado.app.ui.main.bookshelf.style2.BookshelfFolderRoute
import io.legado.app.ui.main.explore.ExploreHomeRoute
import io.legado.app.ui.main.my.MyRoute
import io.legado.app.ui.main.rss.MainRssRoute
import io.legado.app.ui.navigation.MainDestination
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
internal fun MainDestinationHost(activity: MainActivity, destination: MainDestination) {
    val migration by activity.hostMigration.collectAsStateWithLifecycle()
    if (!migration.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val error = migration.error
            if (error == null) Text("正在恢复主界面")
            else Button(onClick = activity::retryHostMigration) { Text("恢复失败，点击重试：$error") }
        }
        return
    }
    when (destination) {
        MainDestination.Bookshelf -> BookshelfDestination(activity)
        MainDestination.Explore ->
            ExploreHomeRoute(
                activity.exploreHomeModel,
                ready = { activity.destinationReady(MainDestination.Explore) },
                onNative = activity::openExplorePrepared,
            )
        MainDestination.Rss -> {
            val context = LocalContext.current
            val images = remember(context) { GlideRssArticleImageRepository(context) }
            MainRssRoute(
                activity.mainRssModel,
                images,
                ready = { activity.destinationReady(MainDestination.Rss) },
                native = activity::openRssPrepared,
            )
        }
        MainDestination.My ->
            MyRoute(
                activity.myViewModel,
                isMore = false,
                onItemClick = activity::openMyItem,
                onLongClick = activity::showMyServiceActions,
                onHelp = activity::showMyHelp,
                onBack = {},
            )
    }
}

@Composable
private fun BookshelfDestination(activity: MainActivity) {
    BookshelfTransferEffects(activity)
    Box(Modifier.fillMaxSize()) {
        if (activity.uiStateBookshelfStyle == 1) {
            BookshelfFolderRoute(
                viewModel = activity.bookshelfFolderModel,
                onEditGroup = activity::editBookshelfGroup,
                onOpenBook = activity::openBookshelfBook,
                onBookInfo = activity::showBookshelfBookInfo,
                onMenu = activity::handleBookshelfMenu,
                onRefresh = activity::refreshBookshelf,
                onContinue = activity::openRecentBook,
                onRecentInfo = activity::showRecentBookInfo,
                onBookKeys = { keys ->
                    activity.bookshelfFolderModel.replaceUpdating(
                        keys.filter(activity.viewModel::isUpdate).toSet()
                    )
                },
            )
        } else {
            BookshelfHomeRoute(
                viewModel = activity.bookshelfHomeModel,
                onReselect = activity::reselectBookshelfGroup,
                onGroupInfo = activity::editBookshelfGroup,
                onMenu = activity::handleBookshelfMenu,
                onContinue = activity::openRecentBook,
                onRecentInfo = activity::showRecentBookInfo,
            ) { group, index, active, modifier ->
                val model = activity.bookshelfPageModel(group, index)
                SideEffect {
                    model.configure(
                        BookshelfPageParameters(
                            index,
                            group.id,
                            group.sort,
                            group.refresh,
                            group.onlyRead,
                        )
                    )
                }
                BookshelfPageRoute(
                    model,
                    {
                        if (model.state.value.canRefresh)
                            activity.viewModel.upToc(model.getBooks(), group.onlyRead)
                    },
                    activity::openBookshelfBook,
                    activity::showBookshelfBookInfo,
                    { keys ->
                        model.replaceUpdating(keys.filter(activity.viewModel::isUpdate).toSet())
                    },
                    modifier,
                    active = active,
                )
            }
        }
        LegacyBookshelfTransferNotices(activity)
    }
}

@Composable
private fun LegacyBookshelfTransferNotices(activity: MainActivity) {
    val states by activity.legacyTransferStates.collectAsStateWithLifecycle()
    if (states.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        states.forEach { state ->
            Surface(tonalElevation = 4.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.operationLabel ?: "有一项书架操作等待恢复",
                        modifier = Modifier.weight(1f),
                    )
                    if (state.progress != null) Text(" ${state.progress}")
                    if (state.needsFileImportRecovery) {
                        Button(onClick = { activity.retryLegacyFileImport(state.ownerId) }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfTransferEffects(activity: MainActivity) {
    val owner = LocalLifecycleOwner.current
    val transfer = activity.bookshelfTransferModel.transfer
    LaunchedEffect(owner, transfer) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                transfer.addProgress.collect { count ->
                    if (count < 0) return@collect
                    yield()
                    val manager = activity.supportFragmentManager
                    if (
                        transfer.addProgress.value >= 0 &&
                            !manager.isStateSaved &&
                            manager.findFragmentByTag("BookshelfAddProgressDialog") == null
                    ) {
                        BookshelfAddProgressDialog().showNow(manager, "BookshelfAddProgressDialog")
                    }
                }
            }
            launch {
                transfer.pendingExport.collect { path ->
                    if (path != null) activity.launchExportBookshelf(path)
                }
            }
        }
    }
}
