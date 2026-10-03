package io.legado.app.ui.highlight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.HighlightManagementAction
import io.legado.app.ui.components.LegadoTopAppBar

class HighlightManagementActions(
    val back:()->Unit={}, val action:(HighlightManagementAction,Long?)->Unit={_,_->},
    val select:(String)->Unit={}, val selectAll:(Boolean)->Unit={},
    val enable:(String,Boolean)->Unit={_,_->}, val enableSelection:(Boolean)->Unit={},
    val moveEdge:(String,Boolean)->Unit={_,_->}, val moveSelection:(Boolean)->Unit={},
    val requestDelete:(String?)->Unit={}, val confirmDelete:()->Unit={}, val dismissDelete:()->Unit={},
    val filter:(String?)->Unit={}, val export:(Boolean)->Unit={}, val share:()->Unit={}, val retry:()->Unit={},
    val beginReorder:()->Unit={}, val move:(String,String)->Unit={_,_->}, val finishReorder:()->Unit={},
    val beginSlide:(String)->Unit={}, val slideTo:(String)->Unit={}, val finishSlide:()->Unit={}, val cancelGesture:()->Unit={},
    val dismissExport:()->Unit={}, val copyExport:()->Unit={},
)

@Composable fun HighlightManagementScreen(state:HighlightManagementState,actions:HighlightManagementActions,
    exportSummary:String?=null,modifier:Modifier=Modifier) {
    var menu by remember {mutableStateOf(false)}
    var selectionMenu by remember {mutableStateOf(false)}
    var filter by rememberSaveable {mutableStateOf(false)}
    val selected=state.selected.size;val visible=state.visible
    val allSelected=visible.isNotEmpty() && selected==visible.size
    val canExport=state.canAct && state.draft.exporting==null && state.draft.effects.none{it.action==HighlightManagementAction.Export}
    Surface(modifier.fillMaxSize()) {
        Scaffold(topBar={LegadoTopAppBar(stringResource(R.string.highlight_rule),actions.back,actions={
            IconButton({actions.action(HighlightManagementAction.Add,null)},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-add")) {
                Icon(painterResource(R.drawable.ic_add),stringResource(R.string.highlight_rule_add))
            }
            Box {
                IconButton({menu=true},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-menu")) {Icon(painterResource(R.drawable.ic_more_vert),stringResource(R.string.menu))}
                DropdownMenu(menu,{menu=false}) {
                    HighlightItem(R.string.import_local,"highlight-management-import") {menu=false;actions.action(HighlightManagementAction.Import,null)}
                    HighlightItem(R.string.export_all,"highlight-management-export-all",canExport) {menu=false;actions.export(true)}
                    HighlightItem(R.string.highlight_rule_group_manage,"highlight-management-groups") {menu=false;actions.action(HighlightManagementAction.Groups,null)}
                    HighlightItem(R.string.highlight_rule_group_filter,"highlight-management-filter") {menu=false;filter=true}
                }
            }
        })},bottomBar={Surface(tonalElevation=3.dp) { Column(Modifier.navigationBarsPadding()) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(allSelected,{actions.selectAll(it)},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-all"))
                Text(stringResource(if(allSelected)R.string.select_cancel_count else R.string.select_all_count,selected,visible.size),Modifier.weight(1f))
                TextButton({actions.selectAll(false)},enabled=state.canAct && selected>0,modifier=Modifier.testTag("highlight-management-invert")){Text(stringResource(R.string.revert_selection))}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({actions.requestDelete(null)},enabled=state.canAct && selected>0,modifier=Modifier.testTag("highlight-management-delete-selection")){Text(stringResource(R.string.delete))}
                Box {
                    IconButton({selectionMenu=true},enabled=state.canAct && selected>0,modifier=Modifier.testTag("highlight-management-selection-menu")){Icon(painterResource(R.drawable.ic_more_vert),stringResource(R.string.menu))}
                    DropdownMenu(selectionMenu,{selectionMenu=false}) {
                        HighlightItem(R.string.enable_selection,"highlight-management-enable-selection"){selectionMenu=false;actions.enableSelection(true)}
                        HighlightItem(R.string.disable_selection,"highlight-management-disable-selection"){selectionMenu=false;actions.enableSelection(false)}
                        HighlightItem(R.string.selection_to_top,"highlight-management-top-selection"){selectionMenu=false;actions.moveSelection(true)}
                        HighlightItem(R.string.selection_to_bottom,"highlight-management-bottom-selection"){selectionMenu=false;actions.moveSelection(false)}
                        HighlightItem(R.string.export_selection,"highlight-management-export",canExport){selectionMenu=false;actions.export(false)}
                        HighlightItem(R.string.share_selected_source,"highlight-management-share"){selectionMenu=false;actions.share()}
                    }
                }
            }
        }}}) {padding -> Column(Modifier.padding(padding)) {
            if(state.loading || state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {error ->Row(verticalAlignment=Alignment.CenterVertically) {
                Text(error,Modifier.weight(1f).padding(12.dp),color=MaterialTheme.colorScheme.error)
                TextButton(actions.retry,modifier=Modifier.testTag("highlight-management-retry")){Text(stringResource(R.string.retry))}
            }}
            if(state.loaded && state.rowsReady) {
                val list=rememberLazyListState()
                val drag=rememberHighlightListDrag(list,actions)
                val up=stringResource(R.string.dictionary_move_up);val down=stringResource(R.string.dictionary_move_down)
                if(visible.isEmpty())Text(stringResource(R.string.highlight_rule_empty),Modifier.padding(24.dp).testTag("highlight-management-empty"))
                Box(Modifier.weight(1f)) {
                    LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("highlight-management-list").highlightSlideSelection(list,drag,state.canAct,actions)) {
                        itemsIndexed(visible,key={_,row->row.uuid}) {index,row ->
                            var more by remember {mutableStateOf(false)}
                            Row(Modifier.fillMaxWidth().testTag("highlight-management-row-${row.uuid}"),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(row.uuid in state.draft.selection,{actions.select(row.uuid)},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-select-${row.uuid}"))
                                Text(row.label,Modifier.weight(1f).padding(vertical=16.dp)
                                    .highlightReorder(row.uuid,list,drag,state.canAct,actions)
                                    .clickable(enabled=state.canAct){actions.select(row.uuid)}
                                    .semantics {customActions=buildList {
                                        if(state.canAct && index>0)add(CustomAccessibilityAction(up){actions.beginReorder();actions.move(row.uuid,visible[index-1].uuid);actions.finishReorder();true})
                                        if(state.canAct && index<visible.lastIndex)add(CustomAccessibilityAction(down){actions.beginReorder();actions.move(row.uuid,visible[index+1].uuid);actions.finishReorder();true})
                                    }}.testTag("highlight-management-name-${row.uuid}"),maxLines=2,overflow=TextOverflow.Ellipsis)
                                Switch(row.isEnabled,{actions.enable(row.uuid,it)},enabled=state.canAct,modifier=Modifier.semantics{contentDescription=row.displayName}.testTag("highlight-management-enabled-${row.uuid}"))
                                IconButton({actions.action(HighlightManagementAction.Edit,row.id)},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-edit-${row.uuid}")){Icon(painterResource(R.drawable.ic_edit),stringResource(R.string.edit))}
                                Box {
                                    IconButton({more=true},enabled=state.canAct,modifier=Modifier.testTag("highlight-management-more-${row.uuid}")){Icon(painterResource(R.drawable.ic_more_vert),stringResource(R.string.menu))}
                                    DropdownMenu(more,{more=false}) {
                                        HighlightItem(R.string.to_top,"highlight-management-top-${row.uuid}"){more=false;actions.moveEdge(row.uuid,true)}
                                        HighlightItem(R.string.to_bottom,"highlight-management-bottom-${row.uuid}"){more=false;actions.moveEdge(row.uuid,false)}
                                        HighlightItem(R.string.delete,"highlight-management-delete-${row.uuid}",danger=true){more=false;actions.requestDelete(row.uuid)}
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    HighlightManagementFastScroll(list,Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }}
    }
    if(filter) AlertDialog(onDismissRequest={filter=false},title={Text(stringResource(R.string.highlight_rule_group_filter))},text={
        LazyColumn(Modifier.heightIn(max=320.dp).testTag("highlight-management-filter-list")) {
            item {TextButton({actions.filter(null);filter=false},Modifier.fillMaxWidth().testTag("highlight-filter-all")){Text(stringResource(R.string.all))}}
            item {TextButton({actions.filter(HighlightManagementState.UNGROUPED);filter=false},Modifier.fillMaxWidth().testTag("highlight-filter-ungrouped")){Text(stringResource(R.string.no_group))}}
            items(state.groups,key={it}) {group->TextButton({actions.filter(group);filter=false},Modifier.fillMaxWidth().testTag("highlight-filter-$group")){Text("[$group]")}}
        }
    },confirmButton={},dismissButton={TextButton({filter=false},modifier=Modifier.testTag("highlight-management-filter-cancel")){Text(stringResource(R.string.cancel))}})
    if(state.draft.deletion.isNotEmpty())AlertDialog(onDismissRequest=actions.dismissDelete,title={Text(stringResource(R.string.highlight_rule))},text={
        Text(stringResource(R.string.sure_del)+(state.draft.deletionName?.let{"\n$it"} ?: ""))
    },confirmButton={TextButton(actions.confirmDelete,enabled=state.canAct,modifier=Modifier.testTag("highlight-management-delete-confirm")){Text(stringResource(R.string.yes))}},dismissButton={TextButton(actions.dismissDelete){Text(stringResource(R.string.no))}})
    state.draft.exportResult?.let {url ->AlertDialog(onDismissRequest=actions.dismissExport,title={Text(stringResource(R.string.export_success))},text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
        exportSummary?.let{Text(it,Modifier.testTag("highlight-management-export-summary"))}
        SelectionContainer {Text(url,Modifier.testTag("highlight-management-export-result"))}
    }},confirmButton={TextButton(actions.copyExport,modifier=Modifier.testTag("highlight-management-export-copy")){Text(stringResource(android.R.string.ok))}},dismissButton={TextButton(actions.dismissExport){Text(stringResource(R.string.cancel))}})}
}

@Composable private fun HighlightItem(label:Int,tag:String,enabled:Boolean=true,danger:Boolean=false,onClick:()->Unit) {
    DropdownMenuItem(text={Text(stringResource(label),color=if(danger)MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)},onClick=onClick,enabled=enabled,modifier=Modifier.testTag(tag))
}
