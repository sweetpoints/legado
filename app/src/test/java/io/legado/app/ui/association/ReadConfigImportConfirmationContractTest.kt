package io.legado.app.ui.association

import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationOperationResult
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadConfigImportConfirmationContractTest {
    private val config =
        AssociationSession(
            AssociationInput(
                AssociationHostKind.Online,
                AssociationInputKind.View,
                uris = listOf("legado://import/readConfig?src=https://fixture/config"),
            ),
            phase = AssociationPhase.ReadConfig,
            readConfigFile = "private-read-config.json",
        )

    @Test
    fun restoredReadConfigRequiresConfirmationAndConsumesSuccessfulImportOnlyOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture =
            AssociationStateFixture(
                config,
                mutate = {
                    assertEquals("read-config", it.kind)
                    AssociationOperationResult(message = "Imported configuration")
                },
            )
        try {
            runCurrent()
            assertEquals(0, fixture.acceptedMutations)
            assertEquals(AssociationPhase.ReadConfig, fixture.model.state.value.session!!.phase)
            assertFalse(fixture.model.state.value.busy)
            fixture.model.confirmOperation("read-config")
            runCurrent()
            fixture.model.confirmOperation("read-config")
            runCurrent()
            assertEquals(1, fixture.acceptedMutations)
            assertEquals("Imported configuration", fixture.sessions.current.completionMessage)
            assertEquals(AssociationPhase.Finished, fixture.sessions.current.phase)
        } finally {
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closingReadConfigWithoutConfirmationNeverCallsConfigurationEngine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = AssociationStateFixture(config)
        try {
            runCurrent()
            fixture.model.closeOwnedSession()
            fixture.model.confirmOperation("read-config")
            runCurrent()
            assertEquals(0, fixture.acceptedMutations)
            assertEquals(AssociationPhase.ReadConfig, fixture.sessions.current.phase)
        } finally {
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
