package io.legado.app.ui.book.manage

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.*
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.bookshelf.*
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.*
import java.io.File
import kotlinx.coroutines.Dispatchers

/** Book URLs and full drafts remain private to the ViewModel's durable session. */
class BookshelfManageActivity :
    BaseComposeActivity(), SourcePickerDialog.Callback, GroupSelectDialog.CallBack {
    internal val model by
        viewModels<BookshelfManagementViewModel> {
            viewModelFactory {
                initializer {
                    val application = applicationContext
                    BookshelfManagementViewModel(
                        DefaultBookshelfManagementRepository(AppBookshelfManagementStore()),
                        FileBookshelfManagementDraftRepository(application),
                        createSavedStateHandle(),
                        intent.getLongExtra("groupId", -1L),
                        DefaultBookshelfMaintenanceRepository(
                            AppBookshelfMaintenanceStore(application)
                        ),
                        DefaultBookshelfCoverRepository(AppBookshelfCoverStore(application)),
                        DefaultBookshelfSourceRepository(AppBookshelfSourceStore()),
                    )
                }
            }
        }
    private var groupTicket: String? = null
    private var sourceTicket: String? = null
    private var exportTicket: String? = null
    private val exportDir =
        registerForActivityResult(HandleFileContract()) { result ->
            val id = result.value ?: exportTicket
            if (id != null) model.exportResult(id, result.uri?.toString())
            if (id == exportTicket) exportTicket = null
        }
    private val fragments =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDestroyed(manager: FragmentManager, fragment: Fragment) {
                if (isChangingConfigurations || isFinishing) return
                groupTicket
                    ?.takeIf { fragment.tag == "shelf-group-$it" }
                    ?.let { id ->
                        groupTicket = null
                        model.cancelGroup(id)
                    }
                sourceTicket
                    ?.takeIf { fragment.tag == "shelf-source-$it" }
                    ?.let { id ->
                        sourceTicket = null
                        model.cancelSource(id)
                    }
            }
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        groupTicket = savedInstanceState?.getString("shelfGroupTicket")
        sourceTicket = savedInstanceState?.getString("shelfSourceTicket")
        exportTicket = savedInstanceState?.getString("shelfExportTicket")
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, false)
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        BookshelfManagementRoute(
            model,
            { !isFinishing && !supportFragmentManager.isStateSaved },
            ::handle,
            ::finish,
            { sendToClip(it) },
        )
    }

    private fun handle(prepared: PreparedShelfEffect) {
        val receipt = prepared.receipt
        when (receipt.effect) {
            ShelfManagementEffect.Toast ->
                toastOnUi(
                    if (receipt.args.isEmpty()) getString(receipt.resource)
                    else getString(receipt.resource, *receipt.args.toTypedArray())
                )
            ShelfManagementEffect.UpdateToc -> {
                if (prepared.books.isEmpty()) toastOnUi(R.string.no_book_can_update)
                else {
                    postEvent(EventBus.UP_BOOKS_TOC, prepared.books)
                    toastOnUi(
                        getString(R.string.update_book_info_toc_submitted, prepared.books.size)
                    )
                }
            }
            ShelfManagementEffect.OpenBook ->
                prepared.books.firstOrNull()?.let { book ->
                    startActivity<BookInfoActivity> {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                }
            ShelfManagementEffect.ManageGroups ->
                if (supportFragmentManager.findFragmentByTag("GroupManageDialog") == null)
                    showDialogFragment<GroupManageDialog>()
            ShelfManagementEffect.PickGroup -> {
                val request =
                    model.state.value.draft?.groupRequest?.takeIf { it.id == receipt.id } ?: return
                groupTicket = request.id
                val tag = "shelf-group-${request.id}"
                if (supportFragmentManager.findFragmentByTag(tag) == null)
                    GroupSelectDialog(
                            request.currentGroup,
                            when (request.mode) {
                                ShelfGroupMutation.Add -> 34
                                ShelfGroupMutation.Remove -> 42
                                ShelfGroupMutation.Replace -> if (request.ids.size == 1) 12 else 22
                            },
                        )
                        .show(supportFragmentManager, tag)
            }
            ShelfManagementEffect.PickSource -> {
                sourceTicket = receipt.id
                val tag = "shelf-source-${receipt.id}"
                if (supportFragmentManager.findFragmentByTag(tag) == null)
                    SourcePickerDialog().show(supportFragmentManager, tag)
            }
            ShelfManagementEffect.ExportSources -> {
                val file = File(requireNotNull(receipt.file))
                exportTicket = receipt.id
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    value = receipt.id
                    fileData =
                        HandleFileContract.FileData("bookSource.json", file, "application/json")
                }
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        if (requestCode !in listOf(12, 22, 34, 42)) return
        val id = groupTicket ?: return
        groupTicket = null
        model.groupPicked(id, groupId)
    }

    override fun sourceOnClick(source: BookSource) {
        val id = sourceTicket ?: return
        sourceTicket = null
        model.sourcePicked(id, source.bookSourceUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shelfGroupTicket", groupTicket)
        outState.putString("shelfSourceTicket", sourceTicket)
        outState.putString("shelfExportTicket", exportTicket)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        model.endSelectionGesture(cancel = true)
        model.finishReorder(cancel = true)
        super.onPause()
    }

    override fun onStop() {
        val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }
            .onError { AppLog.put("保存书架管理草稿失败", it) }
        super.onStop()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
        if (isFinishing && !isChangingConfigurations) {
            val captured = model
            captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }
                .onError { AppLog.put("清理书架管理草稿失败", it) }
        }
        super.onDestroy()
    }
}
