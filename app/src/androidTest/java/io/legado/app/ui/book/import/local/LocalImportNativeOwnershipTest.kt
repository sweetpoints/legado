package io.legado.app.ui.book.import.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.Book
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalImportNativeOwnershipTest {
    private class Memory : LocalImportSessions {
        var value: LocalImportCheckpoint? = null

        override suspend fun read(ticket: String) = value

        override suspend fun write(ticket: String, value: LocalImportCheckpoint): Boolean {
            if ((this.value?.revision ?: -1) >= value.revision) return false
            this.value = value
            return true
        }

        override suspend fun close(ticket: String) {
            value = null
        }
    }

    private class Operations(
        private val initialRoot: String? = null,
        private val resolve: suspend (String) -> FileDoc = {
            error("Cancel must not open a provider")
        },
    ) : LocalImportOperations {
        override suspend fun settings() = LocalImportSettings(initialRoot, initialRoot, 0, "", "")

        override suspend fun setRoot(value: String) = Unit

        override suspend fun setStorage(value: String) = Unit

        override suspend fun privateStorage() = "private"

        override suspend fun setSort(value: Int) = Unit

        override suspend fun setScript(value: String) = Unit

        override suspend fun root(value: String): FileDoc = resolve(value)

        override suspend fun list(directory: FileDoc): List<LocalImportFile> {
            val uri = Uri.parse(directory.uri.toString() + "/book.epub")
            return listOf(
                LocalImportFile(
                    LocalImportRow(uri.toString(), directory.name + ".epub", false, 10, 0, false),
                    FileDoc(directory.name + ".epub", false, 10, 0, uri),
                )
            )
        }

        override suspend fun scan(
            directory: FileDoc,
            emit: suspend (List<LocalImportFile>) -> Unit,
        ) = Unit

        override suspend fun canAddGroup() = false

        override suspend fun importFiles(
            files: List<FileDoc>,
            groupName: String?,
        ): LocalImportResult = error("Unused")

        override suspend fun deleteFiles(files: List<FileDoc>) = Unit

        override suspend fun archiveEntries(document: FileDoc): List<String> = emptyList()

        override suspend fun archiveBook(name: String): Book? = null

        override suspend fun importArchive(document: FileDoc, name: String): Book? = null

        override suspend fun readBook(document: FileDoc): Book? = null
    }

    private class Registry : ActivityResultRegistry() {
        val requests = mutableMapOf<String, Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            @Suppress("UNCHECKED_CAST")
            val configure = input as (HandleFileContract.HandleFileParam.() -> Unit)?
            val parameters = HandleFileContract.HandleFileParam().apply { configure?.invoke(this) }
            requests[requireNotNull(parameters.value)] = requestCode
        }

        fun cancel(nonce: String) {
            dispatchResult(
                requireNotNull(requests[nonce]),
                Activity.RESULT_CANCELED,
                null as Intent?,
            )
        }
    }

    private suspend fun await(block: () -> Boolean) =
        withTimeout(5000) {
            while (!block()) delay(10)
        }

    @Test
    fun oldValuelessCancelCannotConsumeANewerFolderOrStorageRequest() = runBlocking {
        for (kind in listOf(LocalImportNativeKind.Folder, LocalImportNativeKind.Storage)) {
            val old = LocalImportNative(UUID.randomUUID().toString(), kind, claimed = true)
            val disk = Memory().apply { value = LocalImportCheckpoint(pending = old) }
            val model =
                withContext(Dispatchers.Main) {
                    LocalImportViewModel(Operations(), LocalImportSession("private", disk, false))
                        .also { it.initialize() }
                }
            val owner = ViewModelStore().apply { put("local", model) }
            val registry = Registry()
            val native =
                LocalImportNativeRegistry(registry) { receipt, result ->
                    if (kind == LocalImportNativeKind.Folder)
                        model.pickedFolder(receipt.nonce, result.uri?.toString())
                    else model.pickedStorage(receipt.nonce, result.uri?.toString())
                }
            try {
                await { model.state.value.registryNative?.nonce == old.nonce }
                withContext(Dispatchers.Main) {
                    native.register(old).launch({ value = old.nonce })
                    if (kind == LocalImportNativeKind.Folder) model.requestFolder()
                    else model.requestStorage()
                }
                await { model.state.value.pending?.nonce != null }
                val newest =
                    withContext(Dispatchers.Main) {
                        requireNotNull(
                            model.claimNative(requireNotNull(model.state.value.pending).nonce)
                        )
                    }
                withContext(Dispatchers.Main) {
                    native.register(newest).launch({ value = newest.nonce })
                    registry.cancel(old.nonce)
                }
                delay(50)
                assertEquals(newest.nonce, disk.value?.pending?.nonce)
                withContext(Dispatchers.Main) { registry.cancel(newest.nonce) }
                await { disk.value?.pending == null }
                assertNull(model.state.value.registryNative)
            } finally {
                withContext(Dispatchers.Main) {
                    native.close()
                    owner.clear()
                }
            }
        }
    }

    @Test
    fun restoredRegistryDeliversValuelessCancelToItsOriginalPrivateReceipt() = runBlocking {
        for (kind in listOf(LocalImportNativeKind.Folder, LocalImportNativeKind.Storage)) {
            val pending = LocalImportNative(UUID.randomUUID().toString(), kind, claimed = true)
            val before = Registry()
            val original =
                LocalImportNativeRegistry(before) { _, _ ->
                    error("Must deliver to recreated owner")
                }
            val savedRegistry = Bundle()
            withContext(Dispatchers.Main) {
                original.register(pending).launch({ value = pending.nonce })
                before.onSaveInstanceState(savedRegistry)
                original.close()
            }
            val disk = Memory().apply { value = LocalImportCheckpoint(pending = pending) }
            val model =
                withContext(Dispatchers.Main) {
                    LocalImportViewModel(Operations(), LocalImportSession("private", disk, false))
                        .also { it.initialize() }
                }
            val owner = ViewModelStore().apply { put("local", model) }
            val after = Registry()
            val restored =
                LocalImportNativeRegistry(after) { receipt, result ->
                    if (kind == LocalImportNativeKind.Folder)
                        model.pickedFolder(receipt.nonce, result.uri?.toString())
                    else model.pickedStorage(receipt.nonce, result.uri?.toString())
                }
            try {
                await { model.state.value.registryNative?.nonce == pending.nonce }
                withContext(Dispatchers.Main) {
                    after.onRestoreInstanceState(savedRegistry)
                    after.dispatchResult(
                        requireNotNull(before.requests[pending.nonce]),
                        Activity.RESULT_CANCELED,
                        null as Intent?,
                    )
                    restored.register(requireNotNull(model.state.value.registryNative))
                }
                await { disk.value?.pending == null }
                assertNull(model.state.value.registryNative)
            } finally {
                withContext(Dispatchers.Main) {
                    restored.close()
                    owner.clear()
                }
            }
        }
    }

    @Test
    fun delayedInitialProviderMetadataCannotReclaimNewPickedDirectory() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations =
            Operations("content://old-folder") { path ->
                if (path == "content://old-folder") {
                    entered.complete(Unit)
                    release.await()
                }
                FileDoc(path.substringAfterLast('/'), true, 0, 0, Uri.parse(path))
            }
        val disk = Memory()
        val model =
            withContext(Dispatchers.Main) {
                LocalImportViewModel(operations, LocalImportSession("private", disk, true)).also {
                    it.initialize()
                }
            }
        val owner = ViewModelStore().apply { put("local", model) }
        try {
            entered.await()
            withContext(Dispatchers.Main) { model.requestFolder() }
            await { model.state.value.pending != null }
            withContext(Dispatchers.Main) {
                val picked =
                    requireNotNull(
                        model.claimNative(requireNotNull(model.state.value.pending).nonce)
                    )
                model.pickedFolder(picked.nonce, "content://new-folder")
            }
            await { model.state.value.rows.any { it.name == "new-folder.epub" } }
            release.complete(Unit)
            delay(50)
            assertEquals("new-folder/", model.state.value.path)
            assertEquals(listOf("new-folder.epub"), model.state.value.rows.map { it.name })
            assertEquals("content://new-folder", disk.value?.root)
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { owner.clear() }
        }
    }
}
