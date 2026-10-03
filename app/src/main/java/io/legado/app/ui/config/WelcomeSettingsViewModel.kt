package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.*
import io.legado.app.model.welcome.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class WelcomePickerEvent(val id: String, val night: Boolean)

internal data class WelcomeSettingsState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val settings: WelcomeSettingsSnapshot? = null,
    val milliseconds: Int = 500,
    val popupNight: Boolean? = null,
    val picker: WelcomePickerEvent? = null,
    val imageRetry: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

private data class WelcomeTimeEdit(val id: Long, val value: Int)

/**
 * Shared arrival ordering prevents an old owner's onStop flush from overriding a newer dialog edit.
 */
private object WelcomeTimeWrites {
    private val sequence = AtomicLong(System.nanoTime())
    private val gate = Mutex()

    fun next() = sequence.incrementAndGet()

    suspend fun persist(repository: WelcomeSettingsRepository, edit: WelcomeTimeEdit) =
        gate.withLock {
            if (edit.id == sequence.get()) repository.milliseconds(edit.value)
        }
}

internal class WelcomeSettingsViewModel(
    private val repository: WelcomeSettingsRepository,
    private val images: WelcomeImageInputRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val session =
        saved.get<String>("welcomeSession")
            ?: UUID.randomUUID().toString().also { saved["welcomeSession"] = it }
    private val mutable =
        MutableStateFlow(
            WelcomeSettingsState(
                popupNight = saved.get<Boolean>("popupNight"),
                picker =
                    saved.get<String>("picker")?.let {
                        WelcomePickerEvent(it, saved.get<Boolean>("pickerNight") == true)
                    },
            )
        )
    val state = mutable.asStateFlow()
    private var stopped = false
    private var generation = 0
    private var revision = 0L
    private var imageInitialized = false
    private var restoredTime = saved.get<Int>("millisecondsEdit")
    private var draft = WelcomeImageDraft()
    private var early: WelcomeImageInput? = null
    private var applied = false
    private var observer: Job? = null
    private var operation: Job? = null
    private var failedAction: (suspend () -> Unit)? = null
    private val imageGate = Mutex()
    private val timeGate = Mutex()
    private val timeEdits = MutableStateFlow<WelcomeTimeEdit?>(null)
    private val writer = viewModelScope.launch {
        timeEdits.filterNotNull().collect { edit ->
            try {
                timeGate.withLock { WelcomeTimeWrites.persist(repository, edit) }
                currentCoroutineContext().ensureActive()
                if (!stopped && timeEdits.value?.id == edit.id) {
                    timeEdits.value = null
                    saved.remove<Int>("millisecondsEdit")
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped && timeEdits.value?.id == edit.id) failure(error)
            }
        }
    }

    init {
        initialize()
    }

    private fun nextRevision() = maxOf(System.nanoTime(), revision + 1).also { revision = it }

    private fun initialize() {
        observer?.cancel()
        val token = ++generation
        mutable.value = state.value.copy(loading = true, failed = false, error = null)
        observer = viewModelScope.launch {
            try {
                if (!imageInitialized) {
                    draft = images.open(session)
                    currentCoroutineContext().ensureActive()
                    revision = maxOf(revision, draft.revision)
                    imageInitialized = true
                    if (draft.input != null)
                        mutable.value = state.value.copy(imageRetry = true, error = "图片处理未完成，请重试")
                }
                repository.observe().collect { value ->
                    currentCoroutineContext().ensureActive()
                    if (!stopped && token == generation) {
                        if (restoredTime == value.milliseconds) {
                            restoredTime = null
                            saved.remove<Int>("millisecondsEdit")
                        }
                        mutable.value =
                            state.value.copy(
                                loading = false,
                                failed = false,
                                settings = value,
                                milliseconds =
                                    timeEdits.value?.value ?: restoredTime ?: value.milliseconds,
                                error = state.value.error ?: restoredTime?.let { "显示时长未保存，请重试" },
                            )
                        if (early != null && !state.value.busy)
                            startImage(early!!.also { early = null })
                    }
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped && token == generation)
                    mutable.value =
                        state.value.copy(
                            loading = false,
                            failed = true,
                            error = error.localizedMessage.orEmpty(),
                        )
            }
        }
    }

    private fun usable() =
        !stopped &&
            !state.value.loading &&
            !state.value.failed &&
            !state.value.busy &&
            state.value.settings != null

    fun milliseconds(value: Int) {
        if (!usable()) return
        val clamped = value.coerceIn(0, 800)
        restoredTime = null
        saved["millisecondsEdit"] = clamped
        timeEdits.value = WelcomeTimeEdit(WelcomeTimeWrites.next(), clamped)
        mutable.value = state.value.copy(milliseconds = clamped, error = null)
    }

    fun step(delta: Int) = milliseconds(state.value.milliseconds + delta)

    fun boolean(key: WelcomeSwitch, value: Boolean) = run { repository.boolean(key, value) }

    fun imageAction(night: Boolean) {
        if (!usable()) return
        if (state.value.settings!!.image(night).isEmpty()) picker(night)
        else {
            saved["popupNight"] = night
            mutable.value = state.value.copy(popupNight = night)
        }
    }

    fun dismissPopup() {
        saved.remove<Boolean>("popupNight")
        mutable.value = state.value.copy(popupNight = null)
    }

    fun picker(night: Boolean) {
        if (!usable() || state.value.picker != null) return
        val event = WelcomePickerEvent(UUID.randomUUID().toString(), night)
        saved["picker"] = event.id
        saved["pickerNight"] = night
        saved["pickerResultConsumed"] = false
        dismissPopup()
        mutable.value = state.value.copy(picker = event)
    }

    fun consumePicker(id: String): Boolean {
        if (state.value.picker?.id != id || !usable()) return false
        saved.remove<String>("picker")
        mutable.value = state.value.copy(picker = null)
        return true
    }

    fun pickedImage(uri: String?, requestCode: Int = 0) {
        if (stopped || saved.get<Boolean>("pickerResultConsumed") == true) return
        val night = saved.get<Boolean>("pickerNight") ?: return
        if (requestCode != 0 && requestCode != (if (night) 222 else 221)) return
        saved.remove<Boolean>("pickerNight")
        saved.remove<String>("picker")
        saved["pickerResultConsumed"] = true
        mutable.value = state.value.copy(picker = null)
        if (uri == null) return
        val input = WelcomeImageInput(UUID.randomUUID().toString(), night, uri)
        if (!usable()) {
            early = input
            return
        }
        startImage(input)
    }

    private fun startImage(input: WelcomeImageInput) {
        run {
            applied = false
            draft = WelcomeImageDraft(input, nextRevision())
            images.write(session, draft)
            currentCoroutineContext().ensureActive()
            applyImage(input)
        }
    }

    private suspend fun applyImage(input: WelcomeImageInput) {
        if (!applied) {
            repository.image(input.night, input.uri)
            currentCoroutineContext().ensureActive()
            applied = true
        }
        imageGate.withLock {
            val cleared = WelcomeImageDraft(revision = nextRevision())
            images.write(session, cleared)
            draft = cleared
        }
        currentCoroutineContext().ensureActive()
        if (!stopped)
            mutable.value =
                state.value.copy(
                    imageRetry = false,
                    message =
                        if (
                            input.uri.startsWith("http:", true) ||
                                input.uri.startsWith("https:", true)
                        )
                            "设定成功"
                        else null,
                )
    }

    fun removeImage(night: Boolean) {
        dismissPopup()
        run { repository.image(night, null) }
    }

    fun retry() {
        if (stopped || state.value.busy) return
        when {
            state.value.failed -> initialize()
            early != null && usable() -> startImage(early!!.also { early = null })
            draft.input != null && usable() -> {
                val input = draft.input!!
                run {
                    images.write(session, draft)
                    currentCoroutineContext().ensureActive()
                    applyImage(input)
                }
            }
            else -> {
                val time = restoredTime ?: timeEdits.value?.value
                if (time != null) milliseconds(time) else failedAction?.let { run(it) }
            }
        }
    }

    private fun run(action: suspend () -> Unit) {
        if (!usable()) return
        mutable.value = state.value.copy(busy = true, error = null, message = null)
        operation = viewModelScope.launch {
            try {
                action()
                currentCoroutineContext().ensureActive()
                val settings = repository.load()
                currentCoroutineContext().ensureActive()
                if (!stopped) {
                    failedAction = null
                    mutable.value =
                        state.value.copy(
                            settings = settings,
                            milliseconds =
                                timeEdits.value?.value ?: restoredTime ?: settings.milliseconds,
                        )
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!stopped) {
                    failedAction = action
                    failure(error)
                }
            } finally {
                if (currentCoroutineContext().isActive && !stopped) {
                    mutable.value = state.value.copy(busy = false)
                    early?.let {
                        early = null
                        startImage(it)
                    }
                }
            }
        }
    }

    private fun failure(error: Exception) {
        mutable.value =
            state.value.copy(
                error = error.localizedMessage.orEmpty(),
                imageRetry = draft.input != null || early != null,
            )
    }

    fun consumeMessage(): String? {
        val message = state.value.message ?: return null
        mutable.value = state.value.copy(message = null)
        return message
    }

    suspend fun flush() {
        if (!stopped)
            timeGate.withLock { timeEdits.value?.let { WelcomeTimeWrites.persist(repository, it) } }
    }

    fun stop() {
        if (!stopped) {
            stopped = true
            generation++
            observer?.cancel()
            operation?.cancel()
            writer.cancel()
        }
    }

    suspend fun release() {
        stop()
        images.release(session)
    }

    override fun onCleared() {
        stop()
    }
}
