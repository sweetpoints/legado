package io.legado.app.data.preferences

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.settings.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OtherSettingsDraftRepositoryTest {
    @Test
    fun hugePrivateTokenAndJsonRestoreWithoutBundleAndClosedOwnerCannotRecreateFiles() =
        runBlocking {
            val context =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val id = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "other-settings-drafts")
            val file = File(directory, "$id.json")
            val first = FileOtherSettingsDraftRepository(context)
            val second = FileOtherSettingsDraftRepository(context)
            try {
                first.open(id)
                first.open(other)
                val payload = "synthetic-token-private".repeat(50000)
                val mutation =
                    OtherMutation(
                        "operation",
                        OtherMutationKind.Text,
                        OtherText.Token.name,
                        text = payload,
                        effects = listOf(OtherEffect.RestartMcp),
                    )
                val value =
                    OtherSettingsDraft(
                        100,
                        OtherEditor.Token,
                        payload,
                        2,
                        9,
                        mutation,
                        listOf(OtherEffectReceipt("receipt", OtherEffect.NotifyMain)),
                    )
                first.write(id, value)
                assertEquals(value, second.open(id))
                first.write(id, value.copy(revision = 99, text = "stale"))
                assertEquals(value, second.open(id))
                withContext(Dispatchers.IO) {
                    file.copyTo(File(file.path + ".bak"), overwrite = true)
                    file.delete()
                }
                assertEquals(value, second.open(id))
                assertTrue(file.isFile)
                first.release(id)
                assertFalse(file.exists())
                assertTrue(File(file.path + ".closed").isFile)
                assertTrue(runCatching { second.write(id, value.copy(revision = 101)) }.isFailure)
                assertTrue(runCatching { second.open(id) }.isFailure)
                assertFalse(file.exists())
                assertEquals(OtherSettingsDraft(), first.open(other))
            } finally {
                withContext(Dispatchers.IO) {
                    listOf(id, other).forEach { session ->
                        directory
                            .listFiles()
                            ?.filter { it.name.startsWith(session) }
                            ?.forEach { it.delete() }
                    }
                }
            }
        }
}
