package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class BackupQrResultRegistryTest {
    private class Registry : ActivityResultRegistry() {
        val launched = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { launched += requestCode }
    }
    @Test fun latePreviousScanCannotDeliverUnderAnotherRequestsNonce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = Registry(); val received = mutableListOf<Pair<String, String?>>()
            val bridge = BackupQrResultRegistry(registry) { id, value -> received += id to value }
            try {
                bridge.launch("old"); val oldCode = registry.launched.single()
                registry.dispatchResult(oldCode, Activity.RESULT_CANCELED, null); assertEquals(listOf("old" to null), received)
                bridge.launch("new"); val newCode = registry.launched.last(); assertNotEquals(oldCode, newCode)
                registry.dispatchResult(oldCode, Activity.RESULT_OK, Intent().putExtra("result", "late-old"))
                assertEquals(listOf("old" to null), received)
                registry.dispatchResult(newCode, Activity.RESULT_OK, Intent().putExtra("result", "new-value"))
                assertEquals(listOf("old" to null, "new" to "new-value"), received)
            } finally { bridge.close() }
        }
    }
    @Test fun rotationRegistersSameOwnedKeyAndConsumesPendingScanWithoutRelaunchingActivity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = Registry(); val received = mutableListOf<Pair<String, String?>>()
            val first = BackupQrResultRegistry(registry) { id, value -> received += id to value }
            first.launch("owned"); val code = registry.launched.single(); first.close()
            registry.dispatchResult(code, Activity.RESULT_OK, Intent().putExtra("result", "restored-value")); assertTrue(received.isEmpty())
            val second = BackupQrResultRegistry(registry) { id, value -> received += id to value }
            try { second.restore("owned"); assertEquals(listOf("owned" to "restored-value"), received); assertEquals(1, registry.launched.size)
                second.restore("owned"); assertEquals(1, received.size)
            } finally { second.close() }
        }
    }
}
