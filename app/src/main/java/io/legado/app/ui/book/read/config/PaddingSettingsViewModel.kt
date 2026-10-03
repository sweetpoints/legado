package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PaddingSettingsUiState(
    val snapshot: PaddingSnapshot,
    val region: PaddingRegion = PaddingRegion.BODY,
    val lockLR: Boolean = false,
    val tracking: Set<PaddingSide> = emptySet(),
    val resetRegion: PaddingRegion? = null,
) {
    val current
        get() = snapshot[region]

    val isTracking
        get() = tracking.isNotEmpty()
}

class PaddingSettingsViewModel(
    private val repository: PaddingSettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val initial = repository.load()
    private val initialRegion =
        savedState.get<String>("padding.region")?.let {
            runCatching { PaddingRegion.valueOf(it) }.getOrNull()
        } ?: PaddingRegion.BODY
    private val mutableState =
        MutableStateFlow(
            PaddingSettingsUiState(
                initial,
                initialRegion,
                savedState["padding.lockLR"]
                    ?: (initial[initialRegion].left == initial[initialRegion].right),
                resetRegion =
                    savedState.get<String>("padding.reset")?.let {
                        runCatching { PaddingRegion.valueOf(it) }.getOrNull()
                    },
            )
        )
    val state = mutableState.asStateFlow()

    private data class Edit(
        val region: PaddingRegion,
        val side: PaddingSide,
        val value: Int,
        val linked: Boolean,
    )

    private var pending: Edit? = null
    private var trailingJob: Job? = null
    private var trackingRegion: PaddingRegion? = null
    private var savedOnDismiss = false

    fun selectRegion(region: PaddingRegion) {
        if (state.value.isTracking) return
        flush()
        val snapshot = repository.load()
        mutableState.value =
            state.value.copy(
                snapshot = snapshot,
                region = region,
                lockLR = snapshot[region].left == snapshot[region].right,
            )
        persist()
    }

    fun setLock(locked: Boolean) {
        if (state.value.isTracking) return
        flush()
        mutableState.value = state.value.copy(lockLR = locked)
        persist()
    }

    fun setShowLine(shown: Boolean) {
        val region = state.value.region
        if (region == PaddingRegion.BODY || state.value.current.showLine == shown) return
        repository.setShowLine(region, shown)
        mutableState.value = state.value.copy(snapshot = repository.load())
    }

    fun startTracking(side: PaddingSide) {
        if (state.value.tracking.contains(side)) return
        if (!state.value.isTracking) trackingRegion = state.value.region
        mutableState.value = state.value.copy(tracking = state.value.tracking + side)
    }

    fun stopTracking(side: PaddingSide) {
        finish(side)
        mutableState.value = state.value.copy(tracking = state.value.tracking - side)
        if (!state.value.isTracking) trackingRegion = null
    }

    fun drag(side: PaddingSide, value: Int) {
        val region = trackingRegion ?: state.value.region
        val edit =
            Edit(
                region,
                side,
                value.coerceIn(0, side.maximum),
                state.value.lockLR && region == state.value.region,
            )
        pending?.let { if (it.region != edit.region || it.side != edit.side) flush() }
        showDraft(edit)
        if (region == PaddingRegion.BODY) {
            pending = edit
            if (trailingJob == null)
                trailingJob = viewModelScope.launch {
                    delay(150)
                    val latest = pending
                    pending = null
                    trailingJob = null
                    if (latest != null) apply(latest)
                }
        } else apply(edit)
    }

    fun finish(side: PaddingSide) {
        val latest = pending
        if (latest?.side == side) flush()
    }

    fun step(side: PaddingSide, delta: Int) {
        if (state.value.isTracking) return
        flush()
        val region = state.value.region
        apply(
            Edit(
                region,
                side,
                (state.value.current[side] + delta).coerceIn(0, side.maximum),
                state.value.lockLR,
            )
        )
    }

    private fun showDraft(edit: Edit) {
        var values = state.value.snapshot[edit.region].with(edit.side, edit.value)
        if (edit.linked && (edit.side == PaddingSide.LEFT || edit.side == PaddingSide.RIGHT)) {
            values =
                values.with(
                    if (edit.side == PaddingSide.LEFT) PaddingSide.RIGHT else PaddingSide.LEFT,
                    edit.value,
                )
        }
        mutableState.value =
            state.value.copy(
                snapshot = PaddingSnapshot(state.value.snapshot.regions + (edit.region to values))
            )
    }

    private fun apply(edit: Edit) {
        repository.apply(edit.region, edit.side, edit.value, edit.linked)
        showDraft(edit)
        savedOnDismiss = false
    }

    fun flush() {
        trailingJob?.cancel()
        trailingJob = null
        val edit = pending
        pending = null
        if (edit != null) apply(edit)
    }

    fun viewDestroyed() {
        flush()
        trackingRegion = null
        mutableState.value = state.value.copy(tracking = emptySet())
    }

    fun dismissed(isChangingConfigurations: Boolean) {
        viewDestroyed()
        if (!isChangingConfigurations && !savedOnDismiss) {
            repository.save()
            savedOnDismiss = true
        }
    }

    fun askReset() {
        if (state.value.isTracking) return
        mutableState.value = state.value.copy(resetRegion = state.value.region)
        persist()
    }

    fun cancelReset() {
        mutableState.value = state.value.copy(resetRegion = null)
        persist()
    }

    fun confirmReset() {
        val region = state.value.resetRegion ?: return
        flush()
        repository.reset(region)
        savedOnDismiss = false
        cancelReset()
        selectRegion(region)
    }

    private fun persist() {
        savedState["padding.region"] = state.value.region.name
        savedState["padding.lockLR"] = state.value.lockLR
        savedState["padding.reset"] = state.value.resetRegion?.name
    }
}
