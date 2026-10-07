package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.legado.app.R

private val shelfMenu =
    listOf(
        R.id.menu_update_toc to R.string.update_toc,
        R.id.menu_add_local to R.string.book_local,
        R.id.menu_remote to R.string.add_remote_book,
        R.id.menu_add_url to R.string.add_url,
        R.id.menu_bookshelf_manage to R.string.bookshelf_management,
        R.id.menu_download to R.string.cache_export,
        R.id.menu_group_manage to R.string.group_manage,
        R.id.menu_bookshelf_layout to R.string.bookshelf_layout,
        R.id.menu_export_bookshelf to R.string.export_bookshelf,
        R.id.menu_import_bookshelf to R.string.import_bookshelf,
        R.id.menu_log to R.string.log,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfToolbar(title: String, onMenu: (Int) -> Unit, onBack: (() -> Unit)? = null) {
    val menuLabel = stringResource(R.string.menu)
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null)
                IconButton(onClick = onBack, modifier = Modifier.testTag("shelf-back")) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
        },
        actions = {
            IconButton(
                onClick = { onMenu(R.id.menu_search) },
                modifier = Modifier.testTag("shelf-search"),
            ) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
            }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier =
                        Modifier.testTag("shelf-menu").semantics { contentDescription = menuLabel },
                ) {
                    Icon(painterResource(R.drawable.ic_more_vert), null)
                }
                DropdownMenu(expanded, { expanded = false }) {
                    shelfMenu.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                expanded = false
                                onMenu(id)
                            },
                            modifier = Modifier.testTag("shelf-menu-$id"),
                        )
                    }
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}
