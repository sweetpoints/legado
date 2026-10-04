package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class OtherSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun clear() {
        Dispatchers.resetMain()
    }

    private class Store : OtherSettingsStore {
        val state = MutableStateFlow(OtherSettingsSnapshot())
        var token = ""
        val writes = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var lateFailure = false
        var deferBoolean = false
        var loadedOverride: OtherSettingsSnapshot? = null

        override fun changes(): Flow<Unit> = state.map { }

        override suspend fun initializeProcessText() {}

        // Only the accepted boolean observation is delayed; unrelated preference reads remain
        // current.
        override suspend fun load() =
            loadedOverride?.let { state.value.copy(switches = it.switches) } ?: state.value

        override suspend fun readText(key: OtherText) =
            if (key == OtherText.Token) token else state.value.texts.getValue(key)

        override suspend fun boolean(key: OtherSwitch, value: Boolean) {
            writes += "boolean:${key.name}:$value"
            val updated =
                (loadedOverride ?: state.value).copy(
                    switches = (loadedOverride ?: state.value).switches + (key to value)
                )
            if (deferBoolean) loadedOverride = updated else state.value = updated
            gate?.let {
                withContext(NonCancellable) {
                    it.await()
                    if (lateFailure) error("late failure")
                }
            }
        }

        override suspend fun number(key: OtherNumber, value: Int) {
            writes += "number:${key.name}:$value"
            state.value = state.value.copy(numbers = state.value.numbers + (key to value))
        }

        override suspend fun text(key: OtherText, value: String?) {
            writes += "text:${key.name}"
            if (key == OtherText.Token) {
                token = value.orEmpty()
                state.value = state.value.copy(tokenConfigured = token.isNotEmpty())
            } else
                state.value = state.value.copy(texts = state.value.texts + (key to value.orEmpty()))
        }

        override suspend fun choice(key: OtherChoice, value: String) {
            writes += "choice:${key.name}:$value"
            state.value = state.value.copy(choices = state.value.choices + (key to value))
        }
    }

    private class Drafts : OtherSettingsDraftRepository {
        var value = OtherSettingsDraft()
        var failOpen = false
        var failCompleted = false
        var failAll = false
        var receiptGate: CompletableDeferred<Unit>? = null

        override suspend fun open(session: String): OtherSettingsDraft {
            if (failOpen) error("open failed")
            return value
        }

        override suspend fun write(session: String, draft: OtherSettingsDraft) {
            if (failAll || failCompleted && draft.mutation == null && draft.effects.isNotEmpty())
                error("private write failed")
            if (draft.effects.isNotEmpty()) receiptGate?.await()
            if (draft.revision >= value.revision) value = draft
        }

        override suspend fun release(session: String) {}
    }

    private inner class Fixture(
        val store: Store = Store(),
        val drafts: Drafts = Drafts(),
        val saved: SavedStateHandle = SavedStateHandle(),
    ) {
        val model =
            OtherSettingsViewModel(DefaultOtherSettingsRepository(store, dispatcher), drafts, saved)
        val owner = ViewModelStore().apply { put("vm", model) }

        fun close() {
            owner.clear()
        }
    }

    @Test
    fun initializationIsReadOnlyAndHugeTokenEditingStaysPrivateUntilConfirmation() =
        runTest(dispatcher) {
            val f = Fixture()
            val huge = "synthetic".repeat(100000)
            try {
                runCurrent()
                assertTrue(f.store.writes.isEmpty())
                f.model.edit(OtherEditor.Token)
                runCurrent()
                f.model.text(huge, 3, 9)
                runCurrent()
                assertEquals(huge, f.drafts.value.text)
                assertEquals(3, f.drafts.value.selectionStart)
                assertTrue(f.store.writes.isEmpty())
                f.saved.keys().forEach {
                    assertFalse(f.saved.get<Any>(it).toString().contains(huge))
                }
                f.model.dismiss()
                runCurrent()
                assertNull(f.drafts.value.editor)
                assertEquals("", f.drafts.value.text)
                assertTrue(f.store.writes.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test
    fun restoredEditorSelectionAndRebootRevisionPersistBeyondDiskBaseline() =
        runTest(dispatcher) {
            val drafts =
                Drafts().apply {
                    value =
                        OtherSettingsDraft(
                            System.nanoTime() + 1_000_000_000_000,
                            OtherEditor.Hosts,
                            "unfinished JSON",
                            2,
                            6,
                        )
                }
            val old = drafts.value.revision
            val f = Fixture(drafts = drafts)
            try {
                runCurrent()
                assertEquals(2, f.model.state.value.draft!!.selectionStart)
                assertEquals(6, f.model.state.value.draft!!.selectionEnd)
                f.model.text("new partial JSON", 1, 3)
                runCurrent()
                assertTrue(drafts.value.revision > old)
                assertEquals("new partial JSON", drafts.value.text)
                assertTrue(f.store.writes.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test
    fun numericEditorRejectsIncompleteOrOutOfRangeTextWithoutChangingPreference() =
        runTest(dispatcher) {
            val f = Fixture()
            try {
                runCurrent()
                f.model.edit(OtherEditor.WebPort)
                runCurrent()
                f.model.text("")
                f.model.confirm()
                assertTrue(f.model.state.value.invalidNumber)
                assertTrue(f.store.writes.isEmpty())
                f.model.text("60001")
                f.model.confirm()
                assertTrue(f.model.state.value.invalidNumber)
                assertTrue(f.store.writes.isEmpty())
                f.model.text("60000")
                f.model.confirm()
                runCurrent()
                assertEquals(listOf("number:WebPort:60000"), f.store.writes)
                assertNull(f.model.state.value.draft!!.editor)
                assertEquals(
                    listOf(OtherEffect.RestartWeb),
                    f.model.state.value.draft!!.effects.map { it.effect },
                )
            } finally {
                f.close()
            }
        }

    @Test
    fun acceptedTokenClearsPrivateInputAndPublishesOnlyDurableMainEffectReceiptOnce() =
        runTest(dispatcher) {
            val f = Fixture()
            try {
                runCurrent()
                f.model.edit(OtherEditor.Token)
                runCurrent()
                f.model.text(" synthetic-token ")
                f.model.confirm()
                runCurrent()
                assertEquals("synthetic-token", f.store.token)
                assertTrue(f.model.state.value.settings!!.tokenConfigured)
                assertFalse(f.model.state.value.settings.toString().contains("synthetic-token"))
                assertEquals("", f.drafts.value.text)
                assertNull(f.drafts.value.mutation)
                val receipt = f.drafts.value.effects.single()
                assertEquals(OtherEffect.RestartMcp, receipt.effect)
                assertTrue(f.model.consumeEffect(receipt.id))
                assertFalse(f.model.consumeEffect(receipt.id))
                runCurrent()
                assertTrue(f.drafts.value.effects.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test
    fun completionWriteFailureBlocksNewEditsAndRetryDoesNotRepeatPreferenceMutation() =
        runTest(dispatcher) {
            val f = Fixture(drafts = Drafts().apply { failCompleted = true })
            try {
                runCurrent()
                f.model.boolean(OtherSwitch.Log, true)
                runCurrent()
                assertTrue(f.model.state.value.pendingCommit)
                assertTrue(f.model.state.value.draft!!.effects.isEmpty())
                f.model.boolean(OtherSwitch.Discovery, false)
                f.model.edit(OtherEditor.Token)
                runCurrent()
                assertEquals(listOf("boolean:Log:true"), f.store.writes)
                f.drafts.failCompleted = false
                f.model.retry()
                runCurrent()
                assertFalse(f.model.state.value.pendingCommit)
                assertEquals(listOf("boolean:Log:true"), f.store.writes)
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.drafts.value.effects.map { it.effect },
                )
            } finally {
                f.close()
            }
        }

    @Test
    fun restoredPreparedOperationRequiresConfirmationAndKeepsItsEffectPlanEvenIfPreferenceWasAlreadyApplied() =
        runTest(dispatcher) {
            val store =
                Store().apply {
                    state.value =
                        state.value.copy(
                            switches = state.value.switches + (OtherSwitch.Log to true)
                        )
                }
            val mutation =
                OtherMutation(
                    "interrupted",
                    OtherMutationKind.Boolean,
                    OtherSwitch.Log.name,
                    boolean = true,
                    effects = listOf(OtherEffect.LogConfiguration),
                )
            val f =
                Fixture(store, Drafts().apply { value = OtherSettingsDraft(mutation = mutation) })
            try {
                runCurrent()
                assertTrue(f.model.state.value.interrupted)
                assertTrue(store.writes.isEmpty())
                assertTrue(f.model.state.value.draft!!.effects.isEmpty())
                f.model.retry()
                runCurrent()
                assertTrue(store.writes.isEmpty())
                f.model.retryMutationConfirmed()
                runCurrent()
                assertTrue(store.writes.isEmpty())
                assertFalse(f.model.state.value.interrupted)
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.drafts.value.effects.map { it.effect },
                )
            } finally {
                f.close()
            }
        }

    @Test
    fun savedConsumptionPrunesOlderDurableEffectsAndRestoresRemainingReceiptInOrder() =
        runTest(dispatcher) {
            val first = OtherEffectReceipt("first", OtherEffect.RestartWeb)
            val second = OtherEffectReceipt("second", OtherEffect.RestartMcp)
            val f =
                Fixture(
                    drafts =
                        Drafts().apply {
                            value = OtherSettingsDraft(effects = listOf(first, second))
                        },
                    saved = SavedStateHandle(mapOf("otherConsumedEffect" to "first")),
                )
            try {
                runCurrent()
                assertEquals(listOf(second), f.model.state.value.draft!!.effects)
                assertFalse(f.model.consumeEffect("first"))
                assertTrue(f.model.consumeEffect("second"))
                runCurrent()
                assertTrue(f.drafts.value.effects.isEmpty())
            } finally {
                f.close()
            }
        }

    @Test
    fun failedInitializationDisablesWritesUntilRetryAndKeepsOriginalDraft() =
        runTest(dispatcher) {
            val drafts =
                Drafts().apply {
                    failOpen = true
                    value = OtherSettingsDraft(editor = OtherEditor.Threads, text = "84")
                }
            val f = Fixture(drafts = drafts)
            try {
                runCurrent()
                assertTrue(f.model.state.value.failed)
                f.model.boolean(OtherSwitch.Log, true)
                f.model.confirm()
                runCurrent()
                assertTrue(f.store.writes.isEmpty())
                drafts.failOpen = false
                f.model.retry()
                runCurrent()
                assertEquals("84", f.model.state.value.draft!!.text)
                f.model.confirm()
                runCurrent()
                assertEquals(listOf("number:Threads:84"), f.store.writes)
            } finally {
                f.close()
            }
        }

    @Test
    fun earlyOwnedDirectoryResultWaitsForInitializationAndRejectsOldNonceAndDuplicate() =
        runTest(dispatcher) {
            val f =
                Fixture(
                    drafts = Drafts().apply { failOpen = true },
                    saved =
                        SavedStateHandle(
                            mapOf("otherTreeTicket" to "owned", "otherTreeEvent" to "owned")
                        ),
                )
            try {
                f.model.pickedBookTree("wrong", "old")
                assertEquals("owned", f.model.bookTreeTicket())
                f.model.pickedBookTree("private-uri", "owned")
                f.model.pickedBookTree("duplicate", "owned")
                runCurrent()
                assertTrue(f.store.writes.isEmpty())
                assertNull(f.model.bookTreeTicket())
                assertNull(f.model.state.value.bookTreeEvent)
                f.drafts.failOpen = false
                f.model.retry()
                runCurrent()
                assertEquals(listOf("text:BookTree"), f.store.writes)
                assertEquals("private-uri", f.store.state.value.texts.getValue(OtherText.BookTree))
            } finally {
                f.close()
            }
        }

    @Test
    fun stoppedNonCooperativeAcceptedWriteCannotPublishLateErrorOrHostEffects() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val store =
                Store().apply {
                    this.gate = gate
                    lateFailure = true
                }
            val f = Fixture(store)
            try {
                runCurrent()
                f.model.boolean(OtherSwitch.Log, true)
                runCurrent()
                val before = f.model.state.value
                f.model.stop()
                gate.complete(Unit)
                runCurrent()
                assertEquals(before, f.model.state.value)
                assertTrue(f.model.state.value.draft!!.effects.isEmpty())
            } finally {
                gate.complete(Unit)
                f.close()
            }
        }

    @Test
    fun externalMultiKeyChangesQueueDurableOrderedEffectsWithoutOverwritingAnActiveForm() =
        runTest(dispatcher) {
            val f = Fixture()
            try {
                runCurrent()
                f.model.edit(OtherEditor.Hosts)
                runCurrent()
                f.model.text("private unfinished JSON", 2, 6)
                runCurrent()
                f.store.state.value =
                    f.store.state.value.copy(
                        switches =
                            f.store.state.value.switches +
                                (OtherSwitch.TokenRequired to false) +
                                (OtherSwitch.ProcessText to false),
                        numbers =
                            f.store.state.value.numbers +
                                (OtherNumber.Threads to 64) +
                                (OtherNumber.BitmapCache to 90),
                    )
                runCurrent()
                assertEquals("private unfinished JSON", f.model.state.value.draft!!.text)
                assertEquals(2, f.model.state.value.draft!!.selectionStart)
                assertEquals(
                    listOf(
                        OtherEffect.RestartWeb,
                        OtherEffect.RestartMcp,
                        OtherEffect.ProcessTextConfiguration,
                        OtherEffect.ThreadsChanged,
                    ),
                    f.drafts.value.effects.map { it.effect },
                )
                assertFalse(
                    f.drafts.value.effects.any { it.effect == OtherEffect.ResizeBitmapCache }
                )
                assertTrue(f.store.writes.isEmpty())
                assertEquals(f.drafts.value.effects, f.model.state.value.draft!!.effects)
            } finally {
                f.close()
            }
        }

    @Test
    fun coalescedOwnObserverSettlesItsBaselineAndCannotSuppressTheNextExternalChange() =
        runTest(dispatcher) {
            val store = Store().apply { deferBoolean = true }
            val f = Fixture(store)
            try {
                runCurrent()
                f.model.boolean(OtherSwitch.Log, true)
                runCurrent()
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.model.state.value.draft!!.effects.map { it.effect },
                )
                f.model.consumeEffect(f.model.state.value.draft!!.effects.single().id)
                runCurrent()
                store.loadedOverride = null
                store.state.value =
                    store.state.value.copy(
                        switches = store.state.value.switches + (OtherSwitch.Log to false)
                    )
                // Emit a fresh snapshot with another field so StateFlow can represent the coalesced
                // change back to false.
                store.state.value =
                    store.state.value.copy(
                        numbers = store.state.value.numbers + (OtherNumber.PreDownload to 3)
                    )
                runCurrent()
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.model.state.value.draft!!.effects.map { it.effect },
                )
                f.model.consumeEffect(f.model.state.value.draft!!.effects.single().id)
                runCurrent()
                store.state.value =
                    store.state.value.copy(
                        switches = store.state.value.switches + (OtherSwitch.Log to true)
                    )
                runCurrent()
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.model.state.value.draft!!.effects.map { it.effect },
                )
                assertEquals(1, store.writes.size)
            } finally {
                f.close()
            }
        }

    @Test
    fun unpublishedExternalReceiptCannotReachHostWhileItsPrivateWriteIsPendingEvenDuringTyping() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val drafts = Drafts().apply { receiptGate = gate }
            val f = Fixture(drafts = drafts)
            try {
                runCurrent()
                f.model.edit(OtherEditor.Hosts)
                runCurrent()
                f.store.state.value =
                    f.store.state.value.copy(
                        numbers = f.store.state.value.numbers + (OtherNumber.WebPort to 6000)
                    )
                runCurrent()
                assertTrue(f.model.state.value.effectsWriting)
                f.model.text("edit while receipt is writing")
                runCurrent()
                val receipt = f.model.state.value.draft!!.effects.single()
                assertFalse(f.model.consumeEffect(receipt.id))
                assertTrue(drafts.value.effects.isEmpty())
                gate.complete(Unit)
                runCurrent()
                assertFalse(f.model.state.value.effectsWriting)
                assertEquals("edit while receipt is writing", f.model.state.value.draft!!.text)
                assertTrue(f.model.consumeEffect(receipt.id))
                runCurrent()
            } finally {
                gate.complete(Unit)
                f.close()
            }
        }

    @Test
    fun firstRestoredPreferenceSnapshotNeverProducesExternalEffectsAndFailedPromotionPreservesAnOpenEditor() =
        runTest(dispatcher) {
            val store =
                Store().apply {
                    state.value =
                        state.value.copy(
                            switches =
                                state.value.switches +
                                    (OtherSwitch.Log to true) +
                                    (OtherSwitch.LiveNotifications to true),
                            numbers = state.value.numbers + (OtherNumber.WebPort to 5000),
                        )
                }
            val f = Fixture(store)
            try {
                runCurrent()
                assertTrue(f.model.state.value.draft!!.effects.isEmpty())
                f.model.edit(OtherEditor.Hosts)
                runCurrent()
                f.model.text("keep this private unfinished JSON", 3, 7)
                runCurrent()
                f.model.promotedNotificationUnavailable()
                runCurrent()
                assertFalse(store.state.value.switches.getValue(OtherSwitch.LiveNotifications))
                assertEquals(OtherEditor.Hosts, f.model.state.value.draft!!.editor)
                assertEquals("keep this private unfinished JSON", f.model.state.value.draft!!.text)
                assertEquals(3, f.model.state.value.draft!!.selectionStart)
                assertTrue(f.model.state.value.draft!!.effects.isEmpty())
                assertEquals(listOf("boolean:LiveNotifications:false"), store.writes)
            } finally {
                f.close()
            }
        }

    @Test
    fun completionRetrySettlesCoalescedOwnObservationAndPreservesConcurrentExternalReceipts() =
        runTest(dispatcher) {
            val store = Store().apply { deferBoolean = true }
            val f = Fixture(store, Drafts().apply { failCompleted = true })
            try {
                runCurrent()
                f.model.boolean(OtherSwitch.Log, true)
                runCurrent()
                assertTrue(f.model.state.value.pendingCommit)
                store.state.value =
                    store.state.value.copy(
                        numbers = store.state.value.numbers + (OtherNumber.Threads to 64)
                    )
                runCurrent()
                f.drafts.failCompleted = false
                f.model.retry()
                runCurrent()
                assertEquals(
                    setOf(OtherEffect.LogConfiguration, OtherEffect.ThreadsChanged),
                    f.drafts.value.effects.map { it.effect }.toSet(),
                )
                while (f.model.state.value.draft!!.effects.isNotEmpty()) {
                    assertTrue(
                        f.model.consumeEffect(f.model.state.value.draft!!.effects.first().id)
                    )
                    runCurrent()
                }
                store.loadedOverride = null
                store.state.value =
                    store.state.value.copy(
                        numbers = store.state.value.numbers + (OtherNumber.PreDownload to 3)
                    )
                runCurrent()
                assertEquals(
                    listOf(OtherEffect.LogConfiguration),
                    f.drafts.value.effects.map { it.effect },
                )
                assertEquals(1, store.writes.size)
            } finally {
                f.close()
            }
        }
}
