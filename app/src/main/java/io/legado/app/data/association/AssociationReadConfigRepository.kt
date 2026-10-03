package io.legado.app.data.association

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.help.DefaultData
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.normalizeUnderlineConfigs
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefInt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splitties.init.appCtx

@Keep data class AssociationReadConfigSnapshot(val configsJson: String, val shareJson: String)

@Keep
data class AssociationReadConfigPlan(
    val previous: AssociationReadConfigSnapshot,
    val target: AssociationReadConfigSnapshot,
    val importedJson: String,
    val defaultsJson: String,
    val importedName: String,
    val completed: Boolean = false,
    val saveAttempted: Boolean? = false,
)

interface AssociationReadConfigEngine {
    fun snapshot(): AssociationReadConfigSnapshot

    fun prepare(bytes: ByteArray): AssociationReadConfigPlan

    fun apply(plan: AssociationReadConfigPlan)
}

/**
 * Exact existing unzip/font/background import and saveNow behavior remains in the shared engine.
 */
class LegacyAssociationReadConfigEngine(private val save: () -> Unit = ReadBookConfig::saveNow) :
    AssociationReadConfigEngine {
    override fun snapshot() =
        AssociationReadConfigSnapshot(
            GSON.toJson(ReadBookConfig.configList),
            GSON.toJson(ReadBookConfig.shareConfig),
        )

    override fun prepare(bytes: ByteArray): AssociationReadConfigPlan {
        val imported = ReadBookConfig.import(bytes)
        val previous = snapshot()
        val defaults =
            if (ReadBookConfig.configList.size < 5) DefaultData.readConfigs else emptyList()
        val targetReferences =
            if (ReadBookConfig.configList.size < 5) defaults.toMutableList()
            else ReadBookConfig.configList.toMutableList()
        val existing = targetReferences.indexOfFirst { it.name == imported.name }
        if (existing >= 0) targetReferences[existing] = imported else targetReferences += imported
        val targets =
            GSON.fromJsonArray<ReadBookConfig.Config>(GSON.toJson(targetReferences)).getOrThrow()
        val shareIndex = targetReferences.indexOfFirst { it === ReadBookConfig.shareConfig }
        val targetShare =
            if (shareIndex >= 0) targets[shareIndex]
            else GSON.fromJsonObject<ReadBookConfig.Config>(previous.shareJson).getOrThrow()
        val underlineTargets = if (shareIndex >= 0) targets else targets + targetShare
        // Replacing/adding an imported object changes the source references, so saveNow performs
        // this same normalization. Snapshot the planned outcome before it changes global state.
        normalizeUnderlineConfigs(underlineTargets, appCtx.getPrefInt(PreferKey.readStyleSelect))
        return AssociationReadConfigPlan(
            previous = previous,
            target = AssociationReadConfigSnapshot(GSON.toJson(targets), GSON.toJson(targetShare)),
            importedJson = GSON.toJson(imported),
            defaultsJson = GSON.toJson(defaults),
            importedName = imported.name,
        )
    }

    override fun apply(plan: AssociationReadConfigPlan) {
        val originals = ReadBookConfig.configList.toList()
        val originalUnderlines = originals.map { it.copy() }
        val originalShare = ReadBookConfig.shareConfig.copy()
        try {
            applyAssociationReadConfig(
                configs = ReadBookConfig.configList,
                imported =
                    GSON.fromJsonObject<ReadBookConfig.Config>(plan.importedJson).getOrThrow(),
                defaultConfigs = {
                    GSON.fromJsonArray<ReadBookConfig.Config>(plan.defaultsJson).getOrThrow()
                },
                save = save,
                transactionLock = ReadBookConfig,
            )
        } catch (failure: Throwable) {
            // The helper restores list identities. saveNow also normalizes mutable underline
            // values before writing; restore them without replacing objects held by other hosts.
            originals.zip(originalUnderlines).forEach { (original, previous) ->
                restoreUnderline(original, previous)
            }
            restoreUnderline(ReadBookConfig.shareConfig, originalShare)
            throw failure
        }
    }

    private fun restoreUnderline(target: ReadBookConfig.Config, previous: ReadBookConfig.Config) {
        target.underlineMode = previous.underlineMode
        target.underlineColor = previous.underlineColor
        target.underlineColorSet = previous.underlineColorSet
        target.underlineWidth = previous.underlineWidth
        target.underlineDistance = previous.underlineDistance
        target.underlineBodyEnabled = previous.underlineBodyEnabled
        target.underlineTitleEnabled = previous.underlineTitleEnabled
        target.underlineConfigVersion = previous.underlineConfigVersion
    }
}

/**
 * A confirmed configuration records its target before save, allowing accepted-write reconciliation.
 */
class AssociationReadConfigRepository(
    private val sessions: FileAssociationSessionRepository,
    private val engine: AssociationReadConfigEngine = LegacyAssociationReadConfigEngine(),
    private val transactionLock: Any = ReadBookConfig,
    private val beforeJournalWrite: (AssociationReadConfigPlan) -> Unit = {},
) {
    suspend fun import(ticket: String, operationToken: String): String =
        withContext(NonCancellable) {
            require(runCatching { UUID.fromString(operationToken) }.isSuccess)
            val session = sessions.read(ticket)
            check(session.operation?.token == operationToken) {
                "Read configuration belongs to another request"
            }
            val filename = checkNotNull(session.readConfigFile)
            val bytes = sessions.readBytes(ticket, filename)
            sessions.withOwnedSession(ticket) { ownedDirectory, current ->
                check(
                    current.operation?.token == operationToken && current.readConfigFile == filename
                ) {
                    "Read configuration owner changed"
                }
                val file = AtomicFile(File(ownedDirectory, "read-config-$operationToken.json"))
                synchronized(transactionLock) {
                    val plan = readPlan(file) ?: engine.prepare(bytes).also { writePlan(file, it) }
                    if (plan.completed) return@synchronized plan.importedName
                    // saveNow writes two separate files and can roll memory back after the first
                    // file was accepted. In-memory equality is not proof of durable acceptance.
                    // A missing flag in an older pending plan is equally unverifiable.
                    check(plan.saveAttempted == false) {
                        "Read configuration save result is uncertain; close and confirm a new import"
                    }
                    val actual = engine.snapshot()
                    when (actual) {
                        plan.previous -> {
                            // Persist this fence before the first global write. Any failure after
                            // it requires a fresh user confirmation, never automatic reapplication.
                            writePlan(file, plan.copy(saveAttempted = true))
                            engine.apply(plan)
                            check(engine.snapshot() == plan.target) {
                                "Read configuration save differs from its plan"
                            }
                        }
                        else ->
                            error(
                                "Read configuration changed elsewhere; close and confirm a new import"
                            )
                    }
                    // Only successful return from both legacy writes authorizes completion. A
                    // failed completion receipt stays uncertain even if memory matches the target.
                    writePlan(file, plan.copy(completed = true, saveAttempted = true))
                    plan.importedName
                }
            }
        }

    private fun readPlan(file: AtomicFile): AssociationReadConfigPlan? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val plan =
            file.openRead().bufferedReader().use {
                GSON.fromJsonObject<AssociationReadConfigPlan>(it.readText()).getOrThrow()
            }
        requireNotNull(plan.previous)
        requireNotNull(plan.target)
        requireNotNull(plan.previous.configsJson)
        requireNotNull(plan.previous.shareJson)
        requireNotNull(plan.target.configsJson)
        requireNotNull(plan.target.shareJson)
        requireNotNull(plan.importedJson)
        requireNotNull(plan.defaultsJson)
        requireNotNull(plan.importedName)
        return plan
    }

    private fun writePlan(file: AtomicFile, plan: AssociationReadConfigPlan) {
        beforeJournalWrite(plan)
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(plan).toByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
