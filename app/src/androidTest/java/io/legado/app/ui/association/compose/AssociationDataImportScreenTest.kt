package io.legado.app.ui.association.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssociationDataImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun backupKeepsFilenameWarningAndExplicitConfirmationSeparateFromCancel() {
        var accepted = 0
        var closed = 0
        compose.setContent {
            MaterialTheme {
                AssociationDataImportScreen(true, "backup.zip", false, { accepted++ }, { closed++ })
            }
        }
        compose
            .onNodeWithText("backup.zip\n${context.getString(R.string.restore_message)}")
            .assertExists()
        compose.runOnIdle { assertEquals(0, accepted) }
        compose.onNodeWithText(context.getString(R.string.ok)).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, accepted)
            assertEquals(1, closed)
        }
    }

    @Test
    fun acceptedImportDisablesBothConfirmationAndCancellation() {
        compose.setContent {
            MaterialTheme { AssociationDataImportScreen(false, "books.json", true, {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.importing)).assertExists()
        compose.onNodeWithText(context.getString(R.string.ok)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsNotEnabled()
    }
}
