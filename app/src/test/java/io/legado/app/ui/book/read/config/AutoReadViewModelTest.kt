package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.AutoReadSettingsRepository
import org.junit.Assert.*
import org.junit.Test

class AutoReadViewModelTest {
    @Test
    fun initialAndEditedSpeedStayWithinSliderRange() {
        val repository = FakeRepository(-5)
        val model = AutoReadViewModel(repository, SavedStateHandle())
        assertEquals(1, model.state.value.speed)
        model.changeSpeed(999)
        assertEquals(120, model.state.value.speed)
        model.changeSpeed(0)
        assertEquals(1, model.state.value.speed)
        assertTrue(repository.saved.isEmpty())
        assertEquals(0, model.state.value.ttsUpdate)
    }

    @Test
    fun editingOnlyUpdatesDraftAndGestureCompletionPersistsBeforeTtsRequest() {
        val repository = FakeRepository(10)
        val model = AutoReadViewModel(repository, SavedStateHandle())
        model.changeSpeed(15)
        model.changeSpeed(30)
        assertTrue(repository.saved.isEmpty())
        model.finishChangingSpeed()
        assertEquals(listOf(30), repository.saved)
        assertEquals(1, model.state.value.ttsUpdate)
        model.ttsUpdated(1)
        assertEquals(0, model.state.value.ttsUpdate)
        assertEquals(listOf(30), repository.saved)
    }

    @Test
    fun restorationKeepsUncommittedDraftWithoutWritingOrRequestingTts() {
        val repository = FakeRepository(10)
        val handle = SavedStateHandle()
        val original = AutoReadViewModel(repository, handle)
        original.changeSpeed(99)
        val restored =
            AutoReadViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(99, restored.state.value.speed)
        assertEquals(0, restored.state.value.ttsUpdate)
        assertEquals(1, repository.reads)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun pendingTtsSurvivesRecreationAndAcknowledgementIsNotRepeated() {
        val repository = FakeRepository(10)
        val handle = SavedStateHandle()
        val original = AutoReadViewModel(repository, handle)
        original.changeSpeed(40)
        original.finishChangingSpeed()
        val restored =
            AutoReadViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(1, restored.state.value.ttsUpdate)
        restored.ttsUpdated(1)
        restored.ttsUpdated(1)
        assertEquals(0, restored.state.value.ttsUpdate)
        assertEquals(listOf(40), repository.saved)
    }

    @Test
    fun staleTtsAcknowledgementDoesNotConsumeNewSpeedChange() {
        val repository = FakeRepository(10)
        val model = AutoReadViewModel(repository, SavedStateHandle())
        model.finishChangingSpeed()
        model.changeSpeed(15)
        model.finishChangingSpeed()
        model.ttsUpdated(1)
        assertEquals(2, model.state.value.ttsUpdate)
        model.ttsUpdated(2)
        assertEquals(0, model.state.value.ttsUpdate)
    }

    @Test
    fun saveFailureKeepsDraftAndDoesNotRequestTtsAndCanRetry() {
        val repository = FakeRepository(10).apply { failSaving = true }
        val model = AutoReadViewModel(repository, SavedStateHandle())
        model.changeSpeed(37)
        model.finishChangingSpeed()
        assertEquals("save failed", model.state.value.error)
        assertEquals(37, model.state.value.speed)
        assertEquals(0, model.state.value.ttsUpdate)
        repository.failSaving = false
        model.finishChangingSpeed()
        assertNull(model.state.value.error)
        assertEquals(listOf(37), repository.saved)
        assertEquals(1, model.state.value.ttsUpdate)
    }

    @Test
    fun counterLeaseDismissAndDestroyReleaseExactlyOnce() {
        val lease = AutoReadDialogLease()
        var count = 0
        if (lease.acquire(count)) count++
        assertFalse(lease.acquire(count))
        if (lease.release()) count--
        if (lease.release()) count--
        assertEquals(0, count)
        assertFalse(lease.isAcquired)
    }

    @Test
    fun rejectedDialogNeverChangesExistingCount() {
        val lease = AutoReadDialogLease()
        var count = 1
        if (lease.acquire(count)) count++
        if (lease.release()) count--
        assertEquals(1, count)
        assertFalse(lease.isAcquired)
    }

    @Test
    fun viewRecreationReleasesOldHostAndAcquiresNewHost() {
        val lease = AutoReadDialogLease()
        var oldHost = 0
        var newHost = 0
        if (lease.acquire(oldHost)) oldHost++
        if (lease.release()) oldHost--
        if (lease.acquire(newHost)) newHost++
        assertEquals(0, oldHost)
        assertEquals(1, newHost)
        if (lease.release()) newHost--
        assertEquals(0, newHost)
    }

    private class FakeRepository(private val initial: Int) : AutoReadSettingsRepository {
        var reads = 0
        var failSaving = false
        val saved = mutableListOf<Int>()

        override fun readSpeed(): Int {
            reads++
            return initial
        }

        override fun saveSpeed(speed: Int) {
            if (failSaving) error("save failed")
            saved += speed
        }
    }
}
