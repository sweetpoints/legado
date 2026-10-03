package io.legado.app.ui.association.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssociationImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun directoryButtonsRetainSeparateSystemAndPrivateActions() {
        var system = 0
        var privateSelections = 0
        content(
            AssociationPhase.Directory,
            system = { system++ },
            privateSelection = { privateSelections++ },
        )
        compose.onNodeWithText(context.getString(R.string.select_folder)).performClick()
        compose
            .onNodeWithText(context.getString(R.string.shared_local_books_private))
            .performClick()
        compose.runOnIdle {
            assertEquals(1, system)
            assertEquals(1, privateSelections)
        }
    }

    @Test
    fun readConfigurationRequiresExplicitConfirmAndCancelRemainsAvailable() {
        var confirmed = 0
        var closed = 0
        content(AssociationPhase.ReadConfig, confirm = { confirmed++ }, close = { closed++ })
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText(context.getString(R.string.ok)).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, closed)
        }
    }

    private fun content(
        phase: AssociationPhase,
        system: () -> Unit = {},
        privateSelection: () -> Unit = {},
        confirm: () -> Unit = {},
        close: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                AssociationImportScreen(
                    AssociationImportState(
                        loaded = true,
                        session =
                            AssociationSession(
                                AssociationInput(
                                    AssociationHostKind.File,
                                    AssociationInputKind.SharedUri,
                                ),
                                phase = phase,
                            ),
                    ),
                    configuredDirectory = "/configured/books",
                    privateDirectory = "/private/books",
                    onConfirmReadConfig = confirm,
                    onConfirmUnsupported = {},
                    onChooseSystemDirectory = system,
                    onChoosePrivateDirectory = privateSelection,
                    onCancelDirectory = close,
                    onClose = close,
                )
            }
        }
    }
}
