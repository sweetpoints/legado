package io.legado.app.ui.browser

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNativeResultRegistryTest {
    @Test
    fun latePickerResultRemainsBoundToTheUuidThatLaunchedIt() {
        val requestCodes = mutableListOf<Int>()
        val registry = RecordingRegistry(requestCodes)
        val owner = BrowserNativeResultRegistry(registry)
        val ticketA = UUID.randomUUID().toString()
        val ticketB = UUID.randomUUID().toString()
        val results = mutableListOf<String>()
        val contract = ActivityResultContracts.StartActivityForResult()

        owner.launcher(ticketA, contract) { ticket, _ -> results.add(ticket) }.launch(Intent())
        owner.launcher(ticketB, contract) { ticket, _ -> results.add(ticket) }.launch(Intent())

        assertTrue(registry.dispatchResult(requestCodes[0], Activity.RESULT_CANCELED, null))
        assertEquals(listOf(ticketA), results)
        assertTrue(registry.dispatchResult(requestCodes[1], Activity.RESULT_CANCELED, null))
        assertEquals(listOf(ticketA, ticketB), results)
        owner.close()
    }

    private class RecordingRegistry(private val requestCodes: MutableList<Int>) :
        ActivityResultRegistry() {
        override fun <Input, Result> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<Input, Result>,
            input: Input,
            options: ActivityOptionsCompat?,
        ) {
            requestCodes.add(requestCode)
        }
    }
}
