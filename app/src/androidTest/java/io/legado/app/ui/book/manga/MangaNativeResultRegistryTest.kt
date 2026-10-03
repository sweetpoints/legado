package io.legado.app.ui.book.manga

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaNativeResultRegistryTest {
    @Test
    fun lateBookInfoResultKeepsNewSessionClaim() {
        verifyLateReceipt("bookInfo", ActivityResultContracts.StartActivityForResult(), Intent())
    }

    @Test
    fun lateCatalogResultKeepsNewSessionClaim() {
        verifyLateReceipt("catalog", TocActivityResult(), "captured-book")
    }

    @Test
    fun lateDirectoryResultKeepsNewSessionClaim() {
        val input: (HandleFileContract.HandleFileParam.() -> Unit)? = { value = "opaque UUID" }
        verifyLateReceipt("imageDirectory", HandleFileContract(), input)
    }

    @Test
    fun restoredPendingResultsRetainCapturedUuidDuringSynchronousRegistration() {
        val first = RecordingRegistry()
        val original = MangaNativeResultRegistry(first)
        val ticketA = UUID.randomUUID().toString()
        val ticketB = UUID.randomUUID().toString()
        val contract = ActivityResultContracts.StartActivityForResult()
        original.launcher("bookInfo", ticketA, contract) { _, _ -> }.launch(Intent())
        original.launcher("bookInfo", ticketB, contract) { _, _ -> }.launch(Intent())
        val saved = Bundle()
        first.onSaveInstanceState(saved)
        original.close()
        val restored = RecordingRegistry()
        restored.onRestoreInstanceState(saved)
        assertTrue(restored.dispatchResult(first.codes[0], Activity.RESULT_CANCELED, null))
        assertTrue(restored.dispatchResult(first.codes[1], Activity.RESULT_CANCELED, null))
        val owner = MangaNativeResultRegistry(restored)
        val accepted = mutableListOf<String>()
        owner.launcher("bookInfo", ticketA, contract) { ticket, _ ->
            if (ticket == ticketB) accepted.add(ticket)
        }
        owner.launcher("bookInfo", ticketB, contract) { ticket, _ ->
            if (ticket == ticketB) accepted.add(ticket)
        }
        assertEquals(listOf(ticketB), accepted)
        owner.close()
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            codes.add(requestCode)
        }
    }

    private fun <Input, Result> verifyLateReceipt(
        kind: String,
        contract: ActivityResultContract<Input, Result>,
        input: Input,
    ) {
        val codes = mutableListOf<Int>()
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    codes.add(requestCode)
                }
            }
        val owner = MangaNativeResultRegistry(registry)
        val ticketA = UUID.randomUUID().toString()
        val ticketB = UUID.randomUUID().toString()
        val receipts = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        var currentClaim = ticketA
        val receive: (String, Result) -> Unit = { ticket, _ ->
            receipts.add(ticket)
            if (ticket == currentClaim) accepted.add(ticket)
        }
        owner.launcher(kind, ticketA, contract, receive).launch(input)
        // A new reader session can launch B while A's old platform result is still in flight.
        currentClaim = ticketB
        owner.launcher(kind, ticketB, contract, receive).launch(input)
        assertEquals(2, codes.distinct().size)
        assertTrue(registry.dispatchResult(codes[0], Activity.RESULT_CANCELED, null))
        assertEquals(listOf(ticketA), receipts)
        assertTrue(accepted.isEmpty())
        assertTrue(registry.dispatchResult(codes[1], Activity.RESULT_CANCELED, null))
        assertEquals(listOf(ticketA, ticketB), receipts)
        assertEquals(listOf(ticketB), accepted)
        owner.close()
    }
}
