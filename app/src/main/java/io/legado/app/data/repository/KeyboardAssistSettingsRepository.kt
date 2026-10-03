package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class KeyboardAssistSettingsRow(
    val id: String,
    val type: Int,
    val key: String,
    val value: String,
    val order: Int,
) {
    fun entity() = KeyboardAssist(type, key, value, order)

    companion object {
        fun from(item: KeyboardAssist): KeyboardAssistSettingsRow {
            val bytes = "${item.type}:${item.key.length}:${item.key}".toByteArray()
            val id =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            return KeyboardAssistSettingsRow(id, item.type, item.key, item.value, item.serialNo)
        }
    }
}

data class KeyboardAssistSettingsText(
    val text: String = "",
    val start: Int = 0,
    val end: Int = start,
) {
    fun bounded() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}

data class KeyboardAssistSettingsDraft(
    val original: KeyboardAssistSettingsRow? = null,
    val key: KeyboardAssistSettingsText = KeyboardAssistSettingsText(original?.key.orEmpty()),
    val value: KeyboardAssistSettingsText = KeyboardAssistSettingsText(original?.value.orEmpty()),
    val revision: Long = 0,
    val open: Boolean = true,
    val planned: KeyboardAssistSettingsRow? = null,
    val beforeOriginal: KeyboardAssistSettingsRow? = null,
    val beforeTarget: KeyboardAssistSettingsRow? = null,
)

interface KeyboardAssistSettingsRepository {
    fun observe(): Flow<List<KeyboardAssistSettingsRow>>

    suspend fun initialRows(): Int

    suspend fun setRows(rows: Int)

    suspend fun loadEditor(session: String, id: String?): KeyboardAssistSettingsDraft

    suspend fun writeEditor(session: String, draft: KeyboardAssistSettingsDraft)

    suspend fun saveEditor(
        session: String,
        draft: KeyboardAssistSettingsDraft,
    ): KeyboardAssistSettingsDraft

    suspend fun delete(id: String)

    suspend fun reorder(ids: List<String>)
}

class RoomKeyboardAssistSettingsRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val directory: File =
        File(context.applicationContext.filesDir, "keyboard-assist-settings"),
    private val failureHook: (String) -> Unit = {},
) : KeyboardAssistSettingsRepository {
    private val context = context.applicationContext

    override fun observe() =
        database.keyboardAssistsDao.flowAll
            .map { it.map(KeyboardAssistSettingsRow::from) }
            .flowOn(Dispatchers.IO)

    override suspend fun initialRows() =
        withContext(Dispatchers.IO) {
            context.defaultSharedPreferences.getInt(PreferKey.showBoardLine, 1).coerceIn(1, 5)
        }

    override suspend fun setRows(rows: Int) =
        withContext(Dispatchers.IO) {
            require(rows in 1..5)
            check(
                context.defaultSharedPreferences
                    .edit()
                    .putInt(PreferKey.showBoardLine, rows)
                    .commit()
            )
            Unit
        }

    private fun all() = database.keyboardAssistsDao.all.map(KeyboardAssistSettingsRow::from)

    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess)
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun read(session: String): KeyboardAssistSettingsDraft? {
        val value = file(session)
        if (!value.baseFile.exists() && !File(value.baseFile.path + ".bak").exists()) return null
        return value.openRead().bufferedReader().use {
            GSON.fromJsonObject<KeyboardAssistSettingsDraft>(it.readText()).getOrThrow()
        }
    }

    private fun write(session: String, draft: KeyboardAssistSettingsDraft) {
        directory.mkdirs()
        val value = file(session)
        val output = value.startWrite()
        try {
            output.write(GSON.toJson(draft).toByteArray())
            value.finishWrite(output)
        } catch (error: Throwable) {
            value.failWrite(output)
            throw error
        }
    }

    private fun complete(
        session: String,
        draft: KeyboardAssistSettingsDraft,
    ): KeyboardAssistSettingsDraft {
        val planned = draft.planned ?: return draft
        failureHook("beforeDatabase")
        database.runInTransaction {
            val current = all()
            val original = draft.original?.let { old -> current.find { it.id == old.id } }
            val target = current.find { it.id == planned.id }
            val alreadyApplied =
                target == planned && (draft.original?.id == planned.id || original == null)
            if (!alreadyApplied) {
                check(original == draft.beforeOriginal && target == draft.beforeTarget) {
                    "Keyboard key changed during save"
                }
                original?.let { database.keyboardAssistsDao.delete(it.entity()) }
                database.keyboardAssistsDao.insert(planned.entity())
            }
        }
        failureHook("afterDatabase")
        val result = draft.copy(open = false, planned = null, revision = draft.revision + 1)
        write(session, result)
        return result
    }

    override suspend fun loadEditor(session: String, id: String?) =
        withContext(Dispatchers.IO) {
            lock(session).withLock {
                read(session)?.let {
                    return@withLock complete(session, it)
                }
                val original = id?.let { target ->
                    checkNotNull(all().find { it.id == target }) { "Keyboard key no longer exists" }
                }
                KeyboardAssistSettingsDraft(original).also { write(session, it) }
            }
        }

    override suspend fun writeEditor(session: String, draft: KeyboardAssistSettingsDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                val current = read(session)
                if (!draft.open && current?.planned != null) {
                    database.runInTransaction {
                        val rows = all()
                        val planned = checkNotNull(current.planned)
                        val original =
                            current.original?.let { old -> rows.find { it.id == old.id } }
                        val target = rows.find { it.id == planned.id }
                        val applied =
                            target == planned &&
                                (current.original?.id == planned.id || original == null)
                        check(!applied) { "Keyboard save is pending; retry to finish it" }
                        // A conflicting external edit prevented this plan from applying. Consume
                        // only its disk ticket, without rolling back or changing any Room row.
                        write(
                            session,
                            current.copy(
                                open = false,
                                planned = null,
                                beforeOriginal = null,
                                beforeTarget = null,
                                revision = maxOf(current.revision, draft.revision) + 1,
                            ),
                        )
                    }
                } else if (
                    current == null ||
                        current.open &&
                            current.planned == null &&
                            current.revision <= draft.revision
                )
                    write(session, draft)
            }
        }

    override suspend fun saveEditor(session: String, draft: KeyboardAssistSettingsDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                val current = read(session)
                if (current != null && (!current.open || current.planned != null))
                    return@withLock complete(session, current)
                check(current == null || current.revision <= draft.revision) {
                    "A newer keyboard draft exists"
                }
                database.runInTransaction {
                    val rows = all()
                    val original = draft.original?.let { old -> rows.find { it.id == old.id } }
                    val item =
                        KeyboardAssist(
                            key = draft.key.text,
                            value = draft.value.text,
                            serialNo =
                                draft.original?.order
                                    ?: (database.keyboardAssistsDao.maxSerialNo + 1),
                        )
                    val planned = KeyboardAssistSettingsRow.from(item)
                    failureHook("beforeJournal")
                    write(
                        session,
                        draft.copy(
                            planned = planned,
                            revision = draft.revision + 1,
                            beforeOriginal = original,
                            beforeTarget = rows.find { it.id == planned.id },
                        ),
                    )
                }
                complete(session, checkNotNull(read(session)))
            }
        }

    override suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                all().find { it.id == id }?.let { database.keyboardAssistsDao.delete(it.entity()) }
            }
        }

    override suspend fun reorder(ids: List<String>) =
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                val rows = all().associateBy { it.id }
                check(ids.size == rows.size && ids.toSet() == rows.keys) {
                    "Keyboard list changed while sorting"
                }
                val ordered = ids.mapIndexed { index, id ->
                    checkNotNull(rows[id]).copy(order = index + 1).entity()
                }
                database.keyboardAssistsDao.update(*ordered.toTypedArray())
            }
        }

    private fun lock(session: String) =
        locks.getOrPut(File(directory, session).absolutePath) { Mutex() }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
