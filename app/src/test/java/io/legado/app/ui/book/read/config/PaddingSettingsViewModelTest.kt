package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class FakePaddingSettingsRepository : PaddingSettingsRepository {
    var snapshot =
        PaddingSnapshot(
            mapOf(
                PaddingRegion.HEADER to RegionPadding(0, 0, 16, 16),
                PaddingRegion.BODY to RegionPadding(6, 6, 16, 16),
                PaddingRegion.FOOTER to RegionPadding(6, 6, 16, 20),
            )
        )

    data class Applied(
        val region: PaddingRegion,
        val side: PaddingSide,
        val value: Int,
        val linked: Boolean,
    )

    val edits = mutableListOf<Applied>()
    val resets = mutableListOf<PaddingRegion>()
    var saves = 0

    override fun load() = snapshot

    override fun apply(region: PaddingRegion, side: PaddingSide, value: Int, linkSides: Boolean) {
        edits += Applied(region, side, value, linkSides)
        var values = snapshot[region].with(side, value)
        if (linkSides && side in listOf(PaddingSide.LEFT, PaddingSide.RIGHT))
            values =
                values.with(
                    if (side == PaddingSide.LEFT) PaddingSide.RIGHT else PaddingSide.LEFT,
                    value,
                )
        snapshot = PaddingSnapshot(snapshot.regions + (region to values))
    }

    override fun setShowLine(region: PaddingRegion, shown: Boolean) {
        snapshot =
            PaddingSnapshot(snapshot.regions + (region to snapshot[region].copy(showLine = shown)))
    }

    override fun reset(region: PaddingRegion) {
        resets += region
        snapshot =
            PaddingSnapshot(
                snapshot.regions + (region to RegionPadding(1, 2, 3, 3, snapshot[region].showLine))
            )
    }

    override fun save() {
        saves++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaddingSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bodyUses150msTrailingWindowsEvenDuringContinuousDragging() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.drag(PaddingSide.TOP, 10)
            runCurrent()
            advanceTimeBy(100)
            model.drag(PaddingSide.TOP, 20)
            assertEquals(20, model.state.value.current.top)
            assertTrue(repository.edits.isEmpty())
            advanceTimeBy(50)
            runCurrent()
            assertEquals(listOf(20), repository.edits.map { it.value })
            model.drag(PaddingSide.TOP, 30)
            runCurrent()
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(20, 30), repository.edits.map { it.value })
        }

    @Test
    fun dragFinishFlushesLatestAndCanceledTimerNeverDuplicatesIt() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.drag(PaddingSide.TOP, 88)
            runCurrent()
            model.finish(PaddingSide.TOP)
            assertEquals(88, repository.snapshot[PaddingRegion.BODY].top)
            model.finish(PaddingSide.TOP)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, repository.edits.size)
        }

    @Test
    fun switchingSidesFlushesEarlierValueInsteadOfReplacingIt() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.drag(PaddingSide.TOP, 77)
            model.drag(PaddingSide.BOTTOM, 99)
            assertEquals(77, repository.snapshot[PaddingRegion.BODY].top)
            model.flush()
            assertEquals(99, repository.snapshot[PaddingRegion.BODY].bottom)
            assertEquals(
                listOf(PaddingSide.TOP, PaddingSide.BOTTOM),
                repository.edits.map { it.side },
            )
        }

    @Test
    fun regionSwitchFlushesBodyAndHeaderFooterChangesAreImmediate() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.drag(PaddingSide.TOP, 100)
            model.selectRegion(PaddingRegion.HEADER)
            assertEquals(100, repository.snapshot[PaddingRegion.BODY].top)
            model.drag(PaddingSide.TOP, 20)
            assertEquals(20, repository.snapshot[PaddingRegion.HEADER].top)
            model.selectRegion(PaddingRegion.FOOTER)
            model.drag(PaddingSide.BOTTOM, 30)
            assertEquals(30, repository.snapshot[PaddingRegion.FOOTER].bottom)
        }

    @Test
    fun leftRightLockLinksOnlyHorizontalSidesAndUnlockFlushesItsCapturedEdit() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            assertTrue(model.state.value.lockLR)
            model.drag(PaddingSide.LEFT, 42)
            assertEquals(42, model.state.value.current.right)
            model.setLock(false)
            assertEquals(42, repository.snapshot[PaddingRegion.BODY].right)
            model.drag(PaddingSide.RIGHT, 55)
            model.flush()
            assertEquals(42, repository.snapshot[PaddingRegion.BODY].left)
            assertEquals(55, repository.snapshot[PaddingRegion.BODY].right)
            model.setLock(true)
            model.step(PaddingSide.TOP, 1)
            assertEquals(6, repository.snapshot[PaddingRegion.BODY].bottom)
        }

    @Test
    fun simultaneousTrackingKeepsRegionAndActionsLockedUntilAllGesturesFinish() =
        runTest(dispatcher) {
            val model =
                PaddingSettingsViewModel(FakePaddingSettingsRepository(), SavedStateHandle())
            model.startTracking(PaddingSide.TOP)
            model.startTracking(PaddingSide.TOP)
            model.startTracking(PaddingSide.BOTTOM)
            assertEquals(2, model.state.value.tracking.size)
            model.selectRegion(PaddingRegion.HEADER)
            model.setLock(false)
            model.askReset()
            assertEquals(PaddingRegion.BODY, model.state.value.region)
            assertTrue(model.state.value.lockLR)
            assertNull(model.state.value.resetRegion)
            model.stopTracking(PaddingSide.TOP)
            assertTrue(model.state.value.isTracking)
            model.stopTracking(PaddingSide.BOTTOM)
            model.stopTracking(PaddingSide.BOTTOM)
            assertFalse(model.state.value.isTracking)
        }

    @Test
    fun destroyViewFlushesAndRestoresTrackingWhileRotationNeverPersists() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.startTracking(PaddingSide.TOP)
            model.drag(PaddingSide.TOP, 38)
            model.viewDestroyed()
            assertFalse(model.state.value.isTracking)
            assertEquals(38, repository.snapshot[PaddingRegion.BODY].top)
            model.dismissed(isChangingConfigurations = true)
            assertEquals(0, repository.saves)
            model.dismissed(isChangingConfigurations = false)
            model.dismissed(isChangingConfigurations = false)
            assertEquals(1, repository.saves)
        }

    @Test
    fun resetRequiresConfirmationTargetsCapturedRegionAndKeepsLineSetting() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.selectRegion(PaddingRegion.HEADER)
            model.setShowLine(true)
            model.askReset()
            model.cancelReset()
            model.confirmReset()
            assertTrue(repository.resets.isEmpty())
            model.askReset()
            model.selectRegion(PaddingRegion.FOOTER)
            model.confirmReset()
            model.confirmReset()
            assertEquals(listOf(PaddingRegion.HEADER), repository.resets)
            assertEquals(PaddingRegion.HEADER, model.state.value.region)
            assertTrue(model.state.value.current.showLine)
            assertTrue(model.state.value.lockLR)
        }

    @Test
    fun savedStateRestoresRegionManualLockAndResetConfirmation() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val handle = SavedStateHandle()
            val model = PaddingSettingsViewModel(repository, handle)
            model.selectRegion(PaddingRegion.FOOTER)
            model.setLock(true)
            model.askReset()
            val restored =
                PaddingSettingsViewModel(
                    repository,
                    SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
                )
            assertEquals(PaddingRegion.FOOTER, restored.state.value.region)
            assertTrue(restored.state.value.lockLR)
            assertEquals(PaddingRegion.FOOTER, restored.state.value.resetRegion)
            assertTrue(repository.resets.isEmpty())
        }

    @Test
    fun valuesClampAndShowLineNeverMutatesTheBody() =
        runTest(dispatcher) {
            val repository = FakePaddingSettingsRepository()
            val model = PaddingSettingsViewModel(repository, SavedStateHandle())
            model.drag(PaddingSide.TOP, 999)
            model.flush()
            model.drag(PaddingSide.LEFT, 999)
            model.flush()
            assertEquals(400, model.state.value.current.top)
            assertEquals(100, model.state.value.current.left)
            model.setShowLine(true)
            assertFalse(model.state.value.current.showLine)
            model.selectRegion(PaddingRegion.FOOTER)
            model.setShowLine(true)
            assertTrue(model.state.value.current.showLine)
        }
}
