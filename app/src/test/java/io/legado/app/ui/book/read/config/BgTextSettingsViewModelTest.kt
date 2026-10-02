package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BgTextSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<BgTextSettingsViewModel>()
    private class Repository : BgTextSettingsRepository {
        var value = BgTextSettingsSnapshot("read:0:day", "Preset", reviewSvg = "<svg>valid</svg>", reviewColor = 123)
        val writes = mutableListOf<Pair<BgTextSetting, String>>(); val dispatched = mutableListOf<BgTextUpdate>()
        var saves = 0; var restores = 0; var deletes = 0; var canDelete = false; var svgGate: CompletableDeferred<Boolean>? = null
        var assetsGate: CompletableDeferred<List<String>>? = null
        override fun load() = value
        override fun checkpoint() = GSON.toJson(value)
        override fun restore(checkpoint: String) { restores++; value = GSON.fromJsonObject<BgTextSettingsSnapshot>(checkpoint).getOrThrow() }
        override fun set(setting: BgTextSetting, text: String): BgTextUpdate {
            writes += setting to text
            value = when (setting) {
                BgTextSetting.Name -> value.copy(name = text); BgTextSetting.DarkStatus -> value.copy(darkStatus = text.toBoolean())
                BgTextSetting.Alpha -> value.copy(alpha = text.toInt()); BgTextSetting.UnderlineMode -> value.copy(underlineMode = text.toInt())
                BgTextSetting.UnderlineWidth -> value.copy(underlineWidth = text.toInt()); BgTextSetting.UnderlineDistance -> value.copy(underlineDistance = text.toInt())
                BgTextSetting.UnderlineBody -> value.copy(underlineBody = text.toBoolean()); BgTextSetting.UnderlineTitle -> value.copy(underlineTitle = text.toBoolean())
                BgTextSetting.ReviewColor -> value.copy(reviewColor = text.toInt()); BgTextSetting.ReviewScale -> value.copy(reviewScale = text.toInt())
                BgTextSetting.ReviewSvg -> value.copy(reviewSvg = text); BgTextSetting.AssetBackground, BgTextSetting.FileBackground -> value.copy(configuration = text)
                else -> value
            }
            return bgTextUpdate(setting)
        }
        override fun color(color: BgTextColor, value: Int): BgTextUpdate { writes += BgTextSetting.TextColor to "$color:$value"; return BgTextUpdate(codes = listOf(2, 6, 9, 11)) }
        override fun replace(configuration: String, restoreDefault: Boolean): BgTextUpdate { value = value.copy(name = configuration); return BgTextUpdate(codes = if (restoreDefault) listOf(1, 2, 5, 13) else listOf(1, 2, 5)) }
        override fun delete(): Boolean { deletes++; return canDelete }
        override fun putTemplate(name: String, svg: String) { value = value.copy(templates = value.templates.filterNot { it.svg.trim() == svg.trim() } + BgTextTemplate(name, svg)) }
        override fun removeTemplate(svg: String) { value = value.copy(templates = value.templates.filterNot { it.svg == svg }) }
        override suspend fun assets(): List<String> = assetsGate?.await() ?: listOf("paper.png", "night.jpg")
        override suspend fun defaults() = listOf(BgTextPreset("Default", "default"))
        override suspend fun validSvg(svg: String) = svgGate?.await() ?: svg.startsWith("<svg>")
        override fun exportSnapshot() = ReaderBackgroundExportSnapshot("{}", value.name, "body.ttf", "title.ttf", listOf("bg.png"))
        override fun dispatch(update: BgTextUpdate) { dispatched += update }
        override fun save() { saves++ }
    }
    private class Files : ReaderBackgroundFilesRepository {
        val imports = mutableListOf<String>(); val backgrounds = mutableListOf<String>(); val exports = mutableListOf<ReaderBackgroundExportSnapshot>()
        var gate: CompletableDeferred<String>? = null
        override suspend fun export(snapshot: ReaderBackgroundExportSnapshot, directory: String): String { exports += snapshot; return gate?.await() ?: "${snapshot.name}.zip" }
        override suspend fun importFile(uri: String): String { imports += uri; return gate?.await() ?: "imported" }
        override suspend fun importUrl(url: String) = importFile(url)
        override suspend fun storeBackground(uri: String): String { backgrounds += uri; return gate?.await() ?: "stored.png" }
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun model(repo: Repository = Repository(), files: Files = Files(), saved: SavedStateHandle = SavedStateHandle()) = BgTextSettingsViewModel(repo, files, saved).also { models += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun asynchronousCatalogsNeverReplaceEditorDraftAndCancelWritesNothing() = test {
        val repo = Repository(); repo.assetsGate = CompletableDeferred(); val model = model(repo); runCurrent()
        model.edit(BgTextEditorKind.Name); model.text("Draft", 2, 4); repo.assetsGate!!.complete(listOf("paper.png")); runCurrent()
        assertEquals("Draft", model.state.value.editor?.text); assertEquals(2, model.state.value.editor?.start); assertEquals(listOf("paper.png"), model.state.value.assets)
        model.dismissEditor(); assertTrue(repo.writes.isEmpty())
    }
    @Test fun sliderRangesMicrostepsAlphaFinishAndSwitchesPreserveExactEvents() = test {
        val repo = Repository(); val model = model(repo); runCurrent()
        model.slider(BgTextSlider.Width, -1); model.slider(BgTextSlider.Width, 100); model.slider(BgTextSlider.Width, 20)
        model.slider(BgTextSlider.Distance, 60); model.slider(BgTextSlider.Alpha, 25); model.alphaFinished()
        model.darkStatus(false); model.underlineBody(false); model.underlineTitle(false); model.underlineMode(6)
        assertEquals(listOf(BgTextSetting.UnderlineWidth to "0", BgTextSetting.UnderlineWidth to "20"), repo.writes.filter { it.first == BgTextSetting.UnderlineWidth })
        assertEquals("10.0dp", BgTextSlider.Width.display(20)); assertEquals("30.0dp", BgTextSlider.Distance.display(60))
        assertEquals(listOf(listOf(3), listOf(3)), model.state.value.pending.filter { it.update?.codes == listOf(3) }.map { it.update!!.codes })
        assertTrue(model.state.value.pending.any { it.update?.systemUi == true }); assertEquals(6, model.state.value.settings.underlineMode)
    }
    @Test fun editorSelectionRestoresAndBadReviewScaleCannotWriteBeforeValidConfirmation() = test {
        val saved = SavedStateHandle(); val repo = Repository(); val model = model(repo, saved = saved); runCurrent()
        model.edit(BgTextEditorKind.ReviewScale); model.text("201", 1, 2); model.confirmEditor(); assertEquals(BgTextError.ScaleInvalid, model.state.value.editor?.error)
        val restored = model(repo, saved = snapshot(saved)); runCurrent(); assertEquals(1, restored.state.value.editor?.start)
        restored.text("150"); restored.confirmEditor(); assertEquals(150, restored.state.value.settings.reviewScale)
        assertEquals(listOf(9, 11), restored.state.value.pending.single().update?.codes); assertTrue(restored.state.value.pending.single().update!!.reviewCache)
    }
    @Test fun invalidAndLateSvgValidationNeverOverwriteNewInputAndBlankSvgResetsCurrentValue() = test {
        val repo = Repository(); repo.svgGate = CompletableDeferred(); val model = model(repo); runCurrent()
        model.edit(BgTextEditorKind.Svg); model.text("invalid"); model.confirmEditor(); runCurrent(); repo.svgGate!!.complete(false); runCurrent()
        assertEquals(BgTextError.SvgInvalid, model.state.value.editor?.error); assertTrue(repo.writes.isEmpty())
        repo.svgGate = CompletableDeferred(); model.text("<svg>old</svg>"); model.confirmEditor(); runCurrent(); model.text("<svg>new</svg>")
        repo.svgGate!!.complete(true); runCurrent(); assertTrue(repo.writes.isEmpty()); assertEquals("<svg>new</svg>", model.state.value.editor?.text)
        model.text(" "); model.confirmEditor(); assertEquals("", model.state.value.settings.reviewSvg); assertNull(model.state.value.editor)
    }
    @Test fun templatesDeduplicateBySvgRenameApplyAndDeleteWithCancelSafeDrafts() = test {
        val repo = Repository(); val model = model(repo); runCurrent(); model.edit(BgTextEditorKind.Templates)
        model.templateName("First"); runCurrent(); assertEquals(BgTextEditorKind.TemplateName, model.state.value.editor?.kind)
        model.text(" "); model.confirmEditor(); assertEquals(BgTextError.NameEmpty, model.state.value.editor?.error)
        model.text("First"); model.confirmEditor(); assertEquals(1, model.state.value.settings.templates.size)
        val first = model.state.value.settings.templates.single(); model.templateName("ignored", first); model.text("Renamed"); model.confirmEditor()
        assertEquals(listOf("Renamed"), model.state.value.settings.templates.map { it.name })
        model.templateDeleteEditor(model.state.value.settings.templates.single()); model.dismissEditor(); assertEquals(1, model.state.value.settings.templates.size)
        model.templateDeleteEditor(model.state.value.settings.templates.single()); model.templateDelete(); assertTrue(model.state.value.settings.templates.isEmpty())
        assertEquals(listOf(BgTextMessage.TemplateSaved, BgTextMessage.TemplateRenamed, BgTextMessage.TemplateDeleted), model.state.value.pending.mapNotNull { it.message })
    }
    @Test fun colorDraftInvalidHexAndAlphaChannelsRestoreWithoutWritingAndReviewResetIsIdempotent() = test {
        val repo = Repository(); val saved = SavedStateHandle(); val model = model(repo, saved = saved); runCurrent()
        model.openColor(BgTextColor.Underline); model.text("80FF0000"); model.colorChannel(0, 64)
        assertEquals("40FF0000", model.state.value.editor?.text)
        val restored = model(repo, saved = snapshot(saved)); runCurrent(); assertEquals(BgTextColor.Underline, restored.state.value.editor?.color)
        restored.text("bad"); restored.confirmEditor(); assertEquals(BgTextError.ColorInvalid, restored.state.value.editor?.error); assertTrue(repo.writes.isEmpty())
        restored.text("40FF0000"); restored.confirmEditor(); assertEquals("Underline:${0x40ff0000}", repo.writes.single().second)
        restored.resetReviewColor(); restored.resetReviewColor(); assertEquals(1, repo.writes.count { it.first == BgTextSetting.ReviewColor })
    }
    @Test fun capturedPickerTargetSurvivesRecreationAndCanceledOrDuplicateResultsCannotStartWork() = test {
        val repo = Repository(); val files = Files(); val saved = SavedStateHandle(); val model = model(repo, files, saved); runCurrent()
        model.picker(BgTextAction.PickImport); model.picker(BgTextAction.PickImport); assertEquals(1, model.state.value.pending.size)
        val restored = model(repo, files, snapshot(saved)); runCurrent(); restored.pickerResult(BgTextAction.PickImport, "one.zip"); runCurrent()
        restored.pickerResult(BgTextAction.PickImport, "duplicate.zip"); runCurrent(); assertEquals(listOf("one.zip"), files.imports)
        restored.picker(BgTextAction.PickBackground); restored.pickerResult(BgTextAction.PickBackground, null); assertTrue(files.backgrounds.isEmpty())
    }
    @Test fun networkPickerIgnoresCanceledDuplicateAndChangedPresetResults() = test {
        val repo = Repository(); val model = model(repo); runCurrent()
        model.networkImportPickerResult(); assertNull(model.state.value.editor)
        model.picker(BgTextAction.PickImport); model.networkImportPickerResult()
        assertEquals(BgTextEditorKind.ImportUrl, model.state.value.editor?.kind)
        model.dismissEditor(); model.networkImportPickerResult(); assertNull(model.state.value.editor)
        model.picker(BgTextAction.PickImport); repo.value = repo.value.copy(context = "read:1:day")
        model.networkImportPickerResult(); assertNull(model.state.value.editor); assertTrue(model.state.value.pickers.isEmpty())
        assertTrue(repo.writes.isEmpty())
    }
    @Test fun lateImageAndImportCannotReplaceLaterManualBackgroundOrDifferentPreset() = test {
        val repo = Repository(); val files = Files(); files.gate = CompletableDeferred(); val model = model(repo, files); runCurrent()
        model.background("image"); runCurrent(); model.asset("paper.png"); files.gate!!.complete("late.png"); runCurrent()
        assertEquals("paper.png", model.state.value.settings.configuration); assertEquals(0, repo.writes.count { it.first == BgTextSetting.FileBackground })
        files.gate = CompletableDeferred(); model.importFile("old.zip"); runCurrent(); repo.value = repo.value.copy(context = "read:1:day")
        files.gate!!.complete("wrong-preset"); runCurrent(); assertEquals("Preset", repo.value.name); assertNull(model.state.value.work)
    }
    @Test fun externalColorChangeInvalidatesPickerAndLateImportEvenWhenPresetIndexIsUnchanged() = test {
        val repo = Repository(); val files = Files(); val model = model(repo, files); runCurrent()
        model.picker(BgTextAction.PickBackground); repo.value = repo.value.copy(textColor = 123)
        model.pickerResult(BgTextAction.PickBackground, "late.png"); runCurrent(); assertTrue(files.backgrounds.isEmpty())
        files.gate = CompletableDeferred(); model.importFile("late.zip"); runCurrent(); repo.value = repo.value.copy(accentColor = 456)
        files.gate!!.complete("unexpected"); runCurrent(); assertEquals("Preset", repo.value.name)
    }
    @Test fun pendingImportResumesAfterProcessRestoreAndExportUsesCapturedFontAndPresetSnapshot() = test {
        val repo = Repository(); val files = Files(); files.gate = CompletableDeferred(); val saved = SavedStateHandle(); val first = model(repo, files, saved); runCurrent()
        first.importFile("restore.zip"); runCurrent(); val restoredState = snapshot(saved); first.stop(); runCurrent()
        files.gate = null; val restored = model(repo, files, restoredState); runCurrent()
        assertEquals("imported", restored.state.value.settings.name); assertEquals(listOf("restore.zip", "restore.zip"), files.imports)
        restored.picker(BgTextAction.PickExport); repo.value = repo.value.copy(name = "Changed after picker")
        restored.pickerResult(BgTextAction.PickExport, "directory"); runCurrent()
        assertEquals("imported", files.exports.single().name); assertEquals("body.ttf", files.exports.single().textFont); assertEquals("title.ttf", files.exports.single().titleFont)
    }
    @Test fun defaultRestoreDeletionAndPendingEffectsKeepOriginalPayloadAndConsumeOnlyOnce() = test {
        val repo = Repository(); val model = model(repo); runCurrent(); model.restoreDefault(0)
        assertEquals("default", model.state.value.settings.name); val effect = model.state.value.pending.single()
        assertEquals(listOf(1, 2, 5, 13), effect.update!!.codes); model.completed(effect.id); model.completed(effect.id); assertEquals(1, repo.dispatched.size)
        model.deletePreset(); assertEquals(BgTextMessage.MinimumPresets, model.state.value.pending.last().message)
        repo.canDelete = true; model.deletePreset(); val count = repo.deletes; model.deletePreset(); assertEquals(count, repo.deletes)
        assertEquals(BgTextAction.Close, model.state.value.pending.last().action); assertEquals(listOf(1, 2, 5), model.state.value.pending.dropLast(1).last().update!!.codes)
    }
    @Test fun configurationTeardownDoesNotSaveAndClosedRestoreNeverReplacesNewGlobalState() = test {
        val repo = Repository(); val saved = SavedStateHandle(); val model = model(repo, saved = saved); runCurrent()
        model.dismissed(true); assertEquals(0, repo.saves); model.dismissed(false); model.dismissed(false); assertEquals(1, repo.saves)
        repo.value = repo.value.copy(name = "New global configuration"); val restored = model(repo, saved = snapshot(saved)); runCurrent(); restored.dismissed(false)
        assertEquals(0, repo.restores); assertEquals("New global configuration", restored.state.value.settings.name); assertEquals(1, repo.saves)
    }
}
