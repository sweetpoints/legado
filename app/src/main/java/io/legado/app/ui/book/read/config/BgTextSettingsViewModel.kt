package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.ReaderBackgroundExportSnapshot
import io.legado.app.data.repository.ReaderBackgroundFilesRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BgTextEditorKind { Name, ReviewScale, Svg, ImportUrl, TemplateName, Templates, DeleteTemplate, Restore, Color, UnderlineMode }
enum class BgTextError { SvgInvalid, NoSvg, NameEmpty, ScaleInvalid, ColorInvalid }
enum class BgTextMessage { Imported, Exported, BackgroundSet, TemplateSaved, TemplateRenamed, TemplateDeleted, ReviewColorReset, MinimumPresets, Failed }
enum class BgTextAction { SystemUi, PickImport, PickExport, PickBackground, Close, Message }
enum class BgTextWorkKind { ImportFile, ImportUrl, Background, Export, SvgEdit, TemplateApply, TemplatePrepare }
enum class BgTextSlider(val setting: BgTextSetting, val maximum: Int) {
    Alpha(BgTextSetting.Alpha, 100), Width(BgTextSetting.UnderlineWidth, 20), Distance(BgTextSetting.UnderlineDistance, 60);
    fun value(settings: BgTextSettingsSnapshot): Int = when (this) { Alpha -> settings.alpha; Width -> settings.underlineWidth; Distance -> settings.underlineDistance }.coerceIn(0, maximum)
    fun display(progress: Int): String = if (this == Alpha) "$progress%" else "${progress / 2f}dp"
}
data class BgTextEditor(val kind: BgTextEditorKind, val context: String, val text: String = "", val start: Int = text.length,
    val end: Int = start, val svg: String = "", val renaming: Boolean = false, val color: BgTextColor? = null, val error: BgTextError? = null)
data class BgTextEffect(val id: Long, val action: BgTextAction? = null, val update: BgTextUpdate? = null,
    val message: BgTextMessage? = null, val text: String = "")
data class BgTextPickerRequest(val action: BgTextAction, val context: String, val revision: Long, val signature: String, val export: ReaderBackgroundExportSnapshot? = null)
data class BgTextWork(val id: Long, val kind: BgTextWorkKind, val argument: String, val context: String, val revision: Long,
    val detail: String = "", val export: ReaderBackgroundExportSnapshot? = null, val signature: String = "")
data class BgTextSettingsState(val settings: BgTextSettingsSnapshot, val assets: List<String> = emptyList(), val defaults: List<BgTextPreset> = emptyList(),
    val loadingAssets: Boolean = true, val editor: BgTextEditor? = null, val work: BgTextWork? = null,
    val pending: List<BgTextEffect> = emptyList(), val pickers: List<BgTextPickerRequest> = emptyList(), val finished: Boolean = false)
class BgTextSettingsViewModel(private val repository: BgTextSettingsRepository, private val files: ReaderBackgroundFilesRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    init { if (saved.get<Boolean>("bgText.finished") != true) saved.get<String>("bgText.checkpoint")?.let(repository::restore) }
    private var next = saved.get<Long>("bgText.next") ?: 0L
    private var revision = saved.get<Long>("bgText.revision") ?: 0L
    private var workJob: Job? = null
    private var catalogsJob: Job? = null
    private val mutableState = MutableStateFlow(BgTextSettingsState(repository.load(),
        editor = GSON.fromJsonObject<BgTextEditor>(saved.get<String>("bgText.editor")).getOrNull(),
        work = GSON.fromJsonObject<BgTextWork>(saved.get<String>("bgText.work")).getOrNull(),
        pending = GSON.fromJsonArray<BgTextEffect>(saved.get<String>("bgText.effects")).getOrNull().orEmpty(),
        pickers = GSON.fromJsonArray<BgTextPickerRequest>(saved.get<String>("bgText.pickers")).getOrNull().orEmpty(), finished = saved["bgText.finished"] ?: false))
    val state = mutableState.asStateFlow()
    init { persist(); if (!state.value.finished) { loadCatalogs(); state.value.work?.let(::launchWork) } }
    private fun persist() {
        saved["bgText.checkpoint"] = repository.checkpoint(); saved["bgText.next"] = next; saved["bgText.revision"] = revision
        saved["bgText.editor"] = state.value.editor?.let(GSON::toJson); saved["bgText.work"] = state.value.work?.let(GSON::toJson)
        saved["bgText.effects"] = GSON.toJson(state.value.pending); saved["bgText.pickers"] = GSON.toJson(state.value.pickers); saved["bgText.finished"] = state.value.finished
    }
    private fun effect(action: BgTextAction? = null, update: BgTextUpdate? = null, message: BgTextMessage? = null, text: String = "") {
        if (action == null && (update == null || update == BgTextUpdate()) && message == null) return
        mutableState.value = state.value.copy(pending = state.value.pending + BgTextEffect(++next, action, update, message, text))
        persist()
    }
    private fun message(message: BgTextMessage, text: String = "") = effect(BgTextAction.Message, message = message, text = text)
    private fun loadCatalogs() {
        catalogsJob?.cancel(); catalogsJob = viewModelScope.launch {
            try {
                val assets = repository.assets(); val defaults = repository.defaults()
                mutableState.value = state.value.copy(assets = assets, defaults = defaults, loadingAssets = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(loadingAssets = false); message(BgTextMessage.Failed, error.localizedMessage.orEmpty()) }
        }
    }
    fun refresh() {
        val settings = repository.load()
        if (settings != state.value.settings) {
            if (state.value.work?.kind != BgTextWorkKind.Export) invalidateWork()
            if (settings.context != state.value.settings.context) mutableState.value = state.value.copy(editor = null)
            revision++
        }
        mutableState.value = state.value.copy(settings = settings); persist()
    }
    private fun invalidateWork() { workJob?.cancel(); workJob = null; mutableState.value = state.value.copy(work = null) }
    private fun editable(): Boolean {
        if (state.value.finished) return false
        if (repository.load().context != state.value.settings.context) { refresh(); return false }
        return true
    }
    private fun changed(setting: BgTextSetting, value: String) {
        if (!editable()) return
        if (state.value.work?.kind != BgTextWorkKind.Export) invalidateWork()
        revision++; val update = repository.set(setting, value)
        mutableState.value = state.value.copy(settings = repository.load()); effect(update = update); persist()
    }
    fun slider(slider: BgTextSlider, value: Int) {
        val progress = value.coerceIn(0, slider.maximum)
        if (progress != slider.value(state.value.settings)) changed(slider.setting, progress.toString())
    }
    fun alphaFinished() { if (!state.value.finished) effect(update = BgTextUpdate(codes = listOf(3))) }
    fun darkStatus(value: Boolean) { if (value != state.value.settings.darkStatus) changed(BgTextSetting.DarkStatus, value.toString()) }
    fun underlineMode(value: Int) { changed(BgTextSetting.UnderlineMode, value.coerceIn(0, 6).toString()); dismissEditor() }
    fun underlineBody(value: Boolean) { if (value != state.value.settings.underlineBody) changed(BgTextSetting.UnderlineBody, value.toString()) }
    fun underlineTitle(value: Boolean) { if (value != state.value.settings.underlineTitle) changed(BgTextSetting.UnderlineTitle, value.toString()) }
    fun asset(name: String) { if (name in state.value.assets) changed(BgTextSetting.AssetBackground, name) }
    fun picker(action: BgTextAction) {
        if (!editable() || action !in listOf(BgTextAction.PickImport, BgTextAction.PickExport, BgTextAction.PickBackground) || state.value.pickers.any { it.action == action }) return
        val settings = repository.load()
        val request = BgTextPickerRequest(action, settings.context, revision, GSON.toJson(settings), if (action == BgTextAction.PickExport) repository.exportSnapshot() else null)
        mutableState.value = state.value.copy(pickers = state.value.pickers + request); effect(action); persist()
    }
    fun pickerResult(action: BgTextAction, uri: String?) {
        val request = state.value.pickers.firstOrNull { it.action == action } ?: return
        mutableState.value = state.value.copy(pickers = state.value.pickers.filterNot { it.action == action }); persist()
        if (uri == null || state.value.finished) return
        if (action != BgTextAction.PickExport && (request.revision != revision || request.context != repository.load().context || request.signature != GSON.toJson(repository.load()))) { refresh(); return }
        when (action) {
            BgTextAction.PickBackground -> background(uri)
            BgTextAction.PickImport -> importFile(uri)
            BgTextAction.PickExport -> begin(BgTextWorkKind.Export, uri, export = request.export)
            else -> Unit
        }
    }
    fun networkImportPickerResult() {
        val request = state.value.pickers.firstOrNull { it.action == BgTextAction.PickImport } ?: return
        val current = repository.load()
        pickerResult(BgTextAction.PickImport, null)
        if (state.value.finished || request.revision != revision || request.context != current.context || request.signature != GSON.toJson(current)) {
            refresh(); return
        }
        edit(BgTextEditorKind.ImportUrl)
    }
    fun edit(kind: BgTextEditorKind) {
        if (state.value.finished) return
        val settings = state.value.settings
        val text = when (kind) { BgTextEditorKind.Name -> settings.name; BgTextEditorKind.ReviewScale -> settings.reviewScale.toString()
            BgTextEditorKind.Svg -> settings.reviewSvg; else -> "" }
        mutableState.value = state.value.copy(editor = BgTextEditor(kind, settings.context, text)); persist()
    }
    fun dismissEditor() {
        if (state.value.work?.kind in listOf(BgTextWorkKind.SvgEdit, BgTextWorkKind.TemplatePrepare, BgTextWorkKind.TemplateApply)) invalidateWork()
        mutableState.value = state.value.copy(editor = null); persist()
    }
    fun text(value: String, start: Int = value.length, end: Int = start) {
        val editor = state.value.editor ?: return
        if (state.value.work?.kind in listOf(BgTextWorkKind.SvgEdit, BgTextWorkKind.TemplatePrepare, BgTextWorkKind.TemplateApply)) invalidateWork()
        mutableState.value = state.value.copy(editor = editor.copy(text = value, start = start.coerceIn(0, value.length), end = end.coerceIn(0, value.length), error = null)); persist()
    }
    private fun error(error: BgTextError) { mutableState.value = state.value.copy(editor = state.value.editor?.copy(error = error)); persist() }
    fun confirmEditor() {
        val editor = state.value.editor ?: return
        if (state.value.finished || editor.context != repository.load().context) { dismissEditor(); refresh(); return }
        when (editor.kind) {
            BgTextEditorKind.Name -> { changed(BgTextSetting.Name, editor.text); dismissEditor() }
            BgTextEditorKind.ReviewScale -> {
                val scale = editor.text.trim().toIntOrNull()
                if (scale == null || scale !in 50..200) { error(BgTextError.ScaleInvalid); return }
                if (scale != state.value.settings.reviewScale) changed(BgTextSetting.ReviewScale, scale.toString())
                dismissEditor()
            }
            BgTextEditorKind.Svg -> {
                val svg = editor.text.trim()
                if (svg.isBlank()) { if (state.value.settings.reviewSvg.isNotEmpty()) changed(BgTextSetting.ReviewSvg, ""); dismissEditor() }
                else begin(BgTextWorkKind.SvgEdit, svg)
            }
            BgTextEditorKind.ImportUrl -> { val url = editor.text.trim(); dismissEditor(); if (url.isNotEmpty()) begin(BgTextWorkKind.ImportUrl, url) }
            BgTextEditorKind.TemplateName -> {
                val name = editor.text.trim()
                if (name.isEmpty()) { error(BgTextError.NameEmpty); return }
                repository.putTemplate(name, editor.svg); revision++; mutableState.value = state.value.copy(settings = repository.load())
                message(if (editor.renaming) BgTextMessage.TemplateRenamed else BgTextMessage.TemplateSaved)
                edit(BgTextEditorKind.Templates)
            }
            BgTextEditorKind.Color -> confirmColor()
            else -> Unit
        }
    }
    fun restoreDefault(index: Int) {
        val preset = state.value.defaults.getOrNull(index) ?: return
        if (!editable()) return
        invalidateWork(); revision++; effect(update = repository.replace(preset.configuration, true)); refresh(); dismissEditor()
    }
    fun deletePreset() {
        if (!editable() || state.value.pending.any { it.action == BgTextAction.Close }) return
        if (!repository.delete()) { message(BgTextMessage.MinimumPresets); return }
        invalidateWork(); revision++; mutableState.value = state.value.copy(settings = repository.load()); effect(update = BgTextUpdate(codes = listOf(1, 2, 5))); effect(BgTextAction.Close)
    }
    fun openColor(color: BgTextColor) {
        if (state.value.finished) return
        val value = state.value.settings.color(color); val hex = if (color == BgTextColor.Underline) "%08X".format(value) else "%06X".format(value and 0xffffff)
        mutableState.value = state.value.copy(editor = BgTextEditor(BgTextEditorKind.Color, state.value.settings.context, hex, color = color)); persist()
    }
    fun colorChannel(channel: Int, value: Int) {
        val editor = state.value.editor ?: return; val color = parseColor(editor) ?: return
        if (channel !in 0..3 || channel == 0 && editor.color != BgTextColor.Underline) return
        val shift = (3 - channel) * 8; val replaced = (color and (255 shl shift).inv()) or (value.coerceIn(0, 255) shl shift)
        text(if (editor.color == BgTextColor.Underline) "%08X".format(replaced) else "%06X".format(replaced and 0xffffff))
    }
    private fun parseColor(editor: BgTextEditor): Int? {
        val hex = editor.text.trim().removePrefix("#"); val expected = if (editor.color == BgTextColor.Underline) 8 else 6
        if (hex.length != expected) return null
        return hex.toLongOrNull(16)?.toInt()?.let { if (expected == 6) it or 0xff000000.toInt() else it }
    }
    private fun confirmColor() {
        val editor = state.value.editor ?: return; val channel = editor.color ?: return; val value = parseColor(editor)
        if (value == null) { error(BgTextError.ColorInvalid); return }
        invalidateWork(); revision++; val update = repository.color(channel, value)
        mutableState.value = state.value.copy(settings = repository.load(), editor = null); effect(update = update); persist()
    }
    fun resetReviewColor() {
        if (!editable()) return
        if (state.value.settings.reviewColor != 0) { changed(BgTextSetting.ReviewColor, "0"); message(BgTextMessage.ReviewColorReset) }
    }
    fun templateName(defaultName: String, template: BgTextTemplate? = null) {
        if (state.value.finished) return
        val svg = template?.svg?.trim() ?: state.value.settings.reviewSvg.trim()
        if (svg.isBlank()) { error(BgTextError.NoSvg); return }
        val existing = template ?: state.value.settings.templates.firstOrNull { it.svg.trim() == svg }
        val name = existing?.name ?: defaultName
        if (template != null) { mutableState.value = state.value.copy(editor = BgTextEditor(BgTextEditorKind.TemplateName, state.value.settings.context, name, svg = svg, renaming = true)); persist() }
        else begin(BgTextWorkKind.TemplatePrepare, svg, name)
    }
    fun templateApply(template: BgTextTemplate) { if (!state.value.finished) begin(BgTextWorkKind.TemplateApply, template.svg.trim()) }
    fun templateDeleteEditor(template: BgTextTemplate) {
        if (!editable()) return
        mutableState.value = state.value.copy(editor = BgTextEditor(BgTextEditorKind.DeleteTemplate, state.value.settings.context, template.name, svg = template.svg)); persist()
    }
    fun templateDelete() {
        if (!editable()) return
        val editor = state.value.editor?.takeIf { it.kind == BgTextEditorKind.DeleteTemplate } ?: return
        repository.removeTemplate(editor.svg); revision++; mutableState.value = state.value.copy(settings = repository.load()); message(BgTextMessage.TemplateDeleted); edit(BgTextEditorKind.Templates)
    }
    fun background(uri: String) = begin(BgTextWorkKind.Background, uri)
    fun importFile(uri: String) = begin(BgTextWorkKind.ImportFile, uri)
    fun importUrl(url: String) = begin(BgTextWorkKind.ImportUrl, url)
    fun export(directory: String) = begin(BgTextWorkKind.Export, directory, export = repository.exportSnapshot())
    private fun begin(kind: BgTextWorkKind, argument: String, detail: String = "", export: ReaderBackgroundExportSnapshot? = null) {
        if (!editable()) return
        invalidateWork()
        val settings = repository.load()
        val work = BgTextWork(++next, kind, argument, settings.context, revision, detail, export, GSON.toJson(settings))
        mutableState.value = state.value.copy(work = work); persist(); launchWork(work)
    }
    private fun launchWork(work: BgTextWork) {
        workJob = viewModelScope.launch {
            try {
                val result = when (work.kind) {
                    BgTextWorkKind.ImportFile -> files.importFile(work.argument); BgTextWorkKind.ImportUrl -> files.importUrl(work.argument)
                    BgTextWorkKind.Background -> files.storeBackground(work.argument)
                    BgTextWorkKind.Export -> files.export(checkNotNull(work.export), work.argument)
                    else -> repository.validSvg(work.argument).toString()
                }
                if (state.value.work?.id != work.id || state.value.finished) return@launch
                if (work.kind != BgTextWorkKind.Export && (revision != work.revision || repository.load().context != work.context || GSON.toJson(repository.load()) != work.signature)) {
                    mutableState.value = state.value.copy(work = null); persist(); refresh(); return@launch
                }
                mutableState.value = state.value.copy(work = null); workJob = null
                when (work.kind) {
                    BgTextWorkKind.ImportFile, BgTextWorkKind.ImportUrl -> { revision++; effect(update = repository.replace(result, false)); refresh(); message(BgTextMessage.Imported) }
                    BgTextWorkKind.Background -> { changed(BgTextSetting.FileBackground, result); message(BgTextMessage.BackgroundSet) }
                    BgTextWorkKind.Export -> message(BgTextMessage.Exported, result)
                    BgTextWorkKind.SvgEdit, BgTextWorkKind.TemplateApply -> {
                        if (result != "true") error(BgTextError.SvgInvalid)
                        else { if (work.argument != state.value.settings.reviewSvg) changed(BgTextSetting.ReviewSvg, work.argument); dismissEditor() }
                    }
                    BgTextWorkKind.TemplatePrepare -> {
                        if (result != "true") error(BgTextError.NoSvg)
                        else { mutableState.value = state.value.copy(editor = BgTextEditor(BgTextEditorKind.TemplateName, work.context, work.detail, svg = work.argument)); persist() }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (state.value.work?.id == work.id && !state.value.finished) { mutableState.value = state.value.copy(work = null); message(BgTextMessage.Failed, error.localizedMessage.orEmpty()) }
            } finally {
                if (state.value.work?.id == work.id) { mutableState.value = state.value.copy(work = null); persist() }
            }
        }
    }
    fun completed(id: Long) {
        val effect = state.value.pending.firstOrNull { it.id == id } ?: return
        mutableState.value = state.value.copy(pending = state.value.pending.filterNot { it.id == id }); persist()
        effect.update?.let(repository::dispatch)
    }
    fun dismissed(changingConfigurations: Boolean) {
        if (changingConfigurations || state.value.finished) return
        invalidateWork(); catalogsJob?.cancel(); mutableState.value = state.value.copy(finished = true); persist(); repository.save()
    }
    fun stop() { workJob?.cancel(); catalogsJob?.cancel() }
    override fun onCleared() { stop(); super.onCleared() }
}
