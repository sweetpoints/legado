package io.legado.app.data.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.normalizeUnderlineConfigs
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationReadConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun acceptedSaveWithFailedReceiptRequiresFreshConfirmationWithoutApplyingAgain() = runBlocking {
        val fixture = fixture()
        val engine = Engine()
        try {
            val failing =
                AssociationReadConfigRepository(
                    fixture.sessions,
                    engine,
                    Any(),
                    beforeJournalWrite = { plan ->
                        if (plan.completed) throw IOException("receipt unavailable")
                    },
                )
            assertTrue(runCatching { failing.import(fixture.ticket, fixture.token) }.isFailure)
            assertEquals(engine.target, engine.actual)
            assertEquals(1, engine.applyCalls)
            val restored = AssociationReadConfigRepository(fixture.sessions, engine, Any())
            repeat(2) {
                val failure = runCatching {
                    restored.import(fixture.ticket, fixture.token)
                }
                    .exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("uncertain"))
            }
            assertEquals(1, engine.applyCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun plannedReceiptFailureDoesNotApplyAndExternalChangeAfterAcceptanceIsNeverOverwritten() =
        runBlocking {
            val fixture = fixture()
            val engine = Engine()
            try {
                val unplanned =
                    AssociationReadConfigRepository(
                        fixture.sessions,
                        engine,
                        Any(),
                        beforeJournalWrite = { throw IOException("plan unavailable") },
                    )
                assertTrue(
                    runCatching { unplanned.import(fixture.ticket, fixture.token) }.isFailure
                )
                assertEquals(engine.previous, engine.actual)
                assertEquals(0, engine.applyCalls)
                val incomplete =
                    AssociationReadConfigRepository(
                        fixture.sessions,
                        engine,
                        Any(),
                        beforeJournalWrite = { plan ->
                            if (plan.completed) throw IOException("receipt unavailable")
                        },
                    )
                assertTrue(
                    runCatching { incomplete.import(fixture.ticket, fixture.token) }.isFailure
                )
                val external =
                    AssociationReadConfigSnapshot("external complete list", "external share")
                engine.actual = external
                val restored = AssociationReadConfigRepository(fixture.sessions, engine, Any())
                assertTrue(runCatching { restored.import(fixture.ticket, fixture.token) }.isFailure)
                assertEquals(external, engine.actual)
                assertEquals(1, engine.applyCalls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun firstConfigFileAcceptedThenShareWriteFailsCannotReplayFromRolledBackMemory() = runBlocking {
        val fixture = fixture()
        val configFile = File(fixture.directory, "actual-config.json")
        val shareFile = File(fixture.directory, "actual-share.json")
        val baseline = AssociationReadConfigSnapshot("old full config", "old full share")
        val target = AssociationReadConfigSnapshot("new full config", "new full share")
        configFile.writeText(baseline.configsJson)
        shareFile.writeText(baseline.shareJson)
        var memory = baseline
        var writes = 0
        val engine =
            object : AssociationReadConfigEngine {
                override fun snapshot() = memory

                override fun prepare(bytes: ByteArray) =
                    AssociationReadConfigPlan(
                        baseline,
                        target,
                        "imported",
                        "defaults",
                        "Imported",
                    )

                override fun apply(plan: AssociationReadConfigPlan) {
                    writes++
                    memory = target
                    configFile.writeText(target.configsJson)
                    // Match the legacy helper rollback after saveNow's second file fails.
                    memory = baseline
                    throw IOException("Share write failed after config accepted")
                }
            }
        try {
            val repository = AssociationReadConfigRepository(fixture.sessions, engine, Any())
            assertTrue(runCatching { repository.import(fixture.ticket, fixture.token) }.isFailure)
            assertEquals(baseline, engine.snapshot())
            assertEquals(target.configsJson, configFile.readText())
            assertEquals(baseline.shareJson, shareFile.readText())
            val restored = AssociationReadConfigRepository(fixture.sessions, engine, Any())
            val failure = runCatching {
                restored.import(fixture.ticket, fixture.token)
            }
                .exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("uncertain"))
            assertEquals(1, writes)
            assertEquals(target.configsJson, configFile.readText())
            assertEquals(baseline.shareJson, shareFile.readText())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun realConfigZipPlanMatchesSaveNormalizationAndFailedSaveRestoresOriginalValuesAndAliases() =
        runBlocking {
            withContext(Dispatchers.IO) {
                synchronized(ReadBookConfig) {
                    val previousList = ReadBookConfig.configList.toList()
                    val previousShare = ReadBookConfig.shareConfig
                    val previousStyle = context.getPrefInt(PreferKey.readStyleSelect)
                    val archive = File(context.externalCache, "readConfig.zip")
                    val importDirectory = File(context.externalCache, "readConfig")
                    val backup =
                        File(context.cacheDir, "association-config-backup-${UUID.randomUUID()}")
                            .apply { mkdirs() }
                    val hadArchive = archive.exists()
                    val hadDirectory = importDirectory.exists()
                    if (hadArchive) archive.copyTo(File(backup, "readConfig.zip"))
                    if (hadDirectory) importDirectory.copyRecursively(File(backup, "readConfig"))
                    try {
                        val originals =
                            (0 until 5).map { index ->
                                ReadBookConfig.Config(
                                    name = "Original $index",
                                    underlineMode = 1,
                                    underlineColor = 10,
                                    underlineColorSet = true,
                                    underlineConfigVersion = 1,
                                )
                            }
                        ReadBookConfig.configList.clear()
                        ReadBookConfig.configList.addAll(originals)
                        ReadBookConfig.shareConfig = originals[0]
                        context.putPrefInt(PreferKey.readStyleSelect, 0)
                        val incoming = originals[0].copy(underlineMode = 2, underlineColor = 20)
                        val bytes =
                            ByteArrayOutputStream().use { output ->
                                ZipOutputStream(output).use { zip ->
                                    zip.putNextEntry(ZipEntry(ReadBookConfig.configFileName))
                                    zip.write(GSON.toJson(incoming).toByteArray())
                                    zip.closeEntry()
                                }
                                output.toByteArray()
                            }
                        val failing = LegacyAssociationReadConfigEngine {
                            normalizeUnderlineConfigs(
                                ReadBookConfig.configList + ReadBookConfig.shareConfig,
                                0,
                            )
                            throw IOException("save unavailable after normalization")
                        }
                        val plan = failing.prepare(bytes)
                        assertTrue(runCatching { failing.apply(plan) }.isFailure)
                        assertEquals(plan.previous, failing.snapshot())
                        assertTrue(ReadBookConfig.configList[0] === originals[0])
                        assertTrue(ReadBookConfig.shareConfig === originals[0])
                        val successful = LegacyAssociationReadConfigEngine {
                            normalizeUnderlineConfigs(
                                ReadBookConfig.configList + ReadBookConfig.shareConfig,
                                0,
                            )
                        }
                        successful.apply(plan)
                        assertEquals(plan.target, successful.snapshot())
                    } finally {
                        ReadBookConfig.configList.clear()
                        ReadBookConfig.configList.addAll(previousList)
                        ReadBookConfig.shareConfig = previousShare
                        context.putPrefInt(PreferKey.readStyleSelect, previousStyle)
                        archive.delete()
                        importDirectory.deleteRecursively()
                        if (hadArchive) File(backup, "readConfig.zip").copyTo(archive)
                        if (hadDirectory)
                            File(backup, "readConfig").copyRecursively(importDirectory)
                        backup.deleteRecursively()
                    }
                }
            }
        }

    private suspend fun fixture(): Fixture {
        val directory = File(context.cacheDir, "association-config-${UUID.randomUUID()}")
        val sessions = FileAssociationSessionRepository(context, directory)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.Online, AssociationInputKind.View))
        val token = UUID.randomUUID().toString()
        sessions.writeBytes(ticket, "confirmed.data", byteArrayOf(1, 2, 3))
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(
                revision = 1,
                phase = AssociationPhase.ReadConfig,
                readConfigFile = "confirmed.data",
                operation =
                    AssociationOperation(token, initial.generation, "read-config", accepted = true),
            ),
        )
        return Fixture(directory, sessions, ticket, token)
    }

    private class Engine : AssociationReadConfigEngine {
        val previous =
            AssociationReadConfigSnapshot("complete original list", "original shared config")
        val target = AssociationReadConfigSnapshot("complete target list", "target shared config")
        var actual = previous
        var applyCalls = 0

        override fun snapshot(): AssociationReadConfigSnapshot = actual

        override fun prepare(bytes: ByteArray) =
            AssociationReadConfigPlan(
                previous,
                target,
                "imported object",
                "default objects",
                "Imported",
            )

        override fun apply(plan: AssociationReadConfigPlan) {
            applyCalls++
            actual = plan.target
        }
    }

    private data class Fixture(
        val directory: File,
        val sessions: FileAssociationSessionRepository,
        val ticket: String,
        val token: String,
    ) {
        suspend fun close() {
            sessions.release(ticket)
            directory.deleteRecursively()
        }
    }
}
