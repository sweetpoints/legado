package io.legado.app.ui.association

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.association.compose.AssociationComposeActivity
import io.legado.app.ui.association.compose.AssociationImportViewModel

/** Track the host after it adopts a private ticket and clears the external share payload. */
internal inline fun <reified A : AssociationComposeActivity> ComposeTestRule.launchAssociation(
    intent: Intent,
): ActivityScenario<A> {
    val scenario = ActivityScenario.launch<A>(intent)
    waitUntil(timeoutMillis = 20000) {
        var normalized = false
        scenario.onActivity { activity ->
            val current = activity.intent
            normalized = current.data == null && current.clipData == null &&
                current.hasExtra(AssociationImportViewModel.TICKET_KEY)
            if (normalized) {
                // ActivityScenario retains this Intent and uses filterEquals for lifecycle
                // callbacks. Follow the sanitized filter without restoring external payloads.
                intent.action = current.action
                intent.setDataAndType(current.data, current.type)
                intent.setPackage(current.`package`)
                intent.categories?.toList()?.forEach(intent::removeCategory)
                current.categories?.forEach(intent::addCategory)
            }
        }
        normalized
    }
    return scenario
}
