package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.TxtTocRuleEditorRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY = "txt.toc.editor."

enum class TxtTocEditorField {
    Name,
    Regex,
    Replacement,
    Example,
}

data class TxtTocEditorCodeRequest(val field: TxtTocEditorField, val text: String, val cursor: Int)

data class TxtTocRuleEditorUiState(
    val name: String = "",
    val regex: String = "",
    val replacement: String = "",
    val example: String = "",
    val cursors: List<Int> = listOf(0, 0, 0, 0),
    val focused: TxtTocEditorField? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val pendingCallback: Boolean = false,
    val confirmExit: Boolean = false,
    val error: String? = null,
) {
    operator fun get(field: TxtTocEditorField) =
        when (field) {
            TxtTocEditorField.Name -> name
            TxtTocEditorField.Regex -> regex
            TxtTocEditorField.Replacement -> replacement
            TxtTocEditorField.Example -> example
        }
}

class TxtTocRuleEditorViewModel(
    private val repository: TxtTocRuleEditorRepository,
    private val saved: SavedStateHandle,
    private val initialId: Long?,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val draftId =
        saved.get<Long>(KEY + "draft.id")
            ?: System.currentTimeMillis().also { saved[KEY + "draft.id"] = it }
    private val restored = saved.get<Boolean>(KEY + "loaded") == true
    private var original =
        if (restored && saved.get<Boolean>(KEY + "hasOriginal") == true)
            TxtTocRuleSnapshot(
                saved[KEY + "id"] ?: draftId,
                saved[KEY + "base.Name"] ?: "",
                saved[KEY + "base.Regex"] ?: "",
                saved[KEY + "base.Replacement"] ?: "",
                saved[KEY + "base.Example"],
                saved[KEY + "order"] ?: -1,
                saved[KEY + "enable"] ?: true,
            )
        else null
    private var loadJob: Job? = null
    private var revision = 0
    private val mutableState =
        MutableStateFlow(
            TxtTocRuleEditorUiState(
                name = saved[KEY + "Name"] ?: "",
                regex = saved[KEY + "Regex"] ?: "",
                replacement = saved[KEY + "Replacement"] ?: "",
                example = saved[KEY + "Example"] ?: "",
                cursors =
                    TxtTocEditorField.entries.map {
                        saved.get<Int>(KEY + "cursor." + it.name) ?: 0
                    },
                focused = saved.get<String>(KEY + "focus")?.let(TxtTocEditorField::valueOf),
                loading = !restored && initialId != null,
                finished = saved[KEY + "finished"] ?: false,
                pendingCallback = saved[KEY + "pendingCallback"] ?: false,
                confirmExit = saved[KEY + "confirmExit"] ?: false,
            )
        )
    val state = mutableState.asStateFlow()

    init {
        if (!restored && initialId != null) load() else if (!restored) markLoaded(null)
    }

    private fun markLoaded(rule: TxtTocRuleSnapshot?) {
        original = rule
        saved[KEY + "loaded"] = true
        saved[KEY + "hasOriginal"] = rule != null
        saved[KEY + "id"] = rule?.id ?: draftId
        saved[KEY + "order"] = rule?.serialNumber ?: -1
        saved[KEY + "enable"] = rule?.enable ?: true
        saved[KEY + "base.Name"] = rule?.name ?: ""
        saved[KEY + "base.Regex"] = rule?.rule ?: ""
        saved[KEY + "base.Replacement"] = rule?.replacement ?: ""
        saved[KEY + "base.Example"] = rule?.example
    }

    fun load() {
        if (state.value.finished || state.value.saving || loadJob?.isActive == true) return
        mutableState.value = state.value.copy(loading = true, loadFailed = false, error = null)
        loadJob = viewModelScope.launch {
            try {
                val rule = initialId?.let { repository.load(it) }
                if (initialId != null && rule == null) error("规则不存在")
                coroutineContext.ensureActive()
                if (state.value.finished) return@launch
                markLoaded(rule)
                TxtTocEditorField.entries.forEach { field ->
                    if (!saved.contains(KEY + field.name))
                        setInput(
                            field,
                            when (field) {
                                TxtTocEditorField.Name -> rule?.name
                                TxtTocEditorField.Regex -> rule?.rule
                                TxtTocEditorField.Replacement -> rule?.replacement
                                TxtTocEditorField.Example -> rule?.example
                            }.orEmpty(),
                            0,
                        )
                }
                mutableState.value = state.value.copy(loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    state.value.copy(
                        loading = false,
                        loadFailed = true,
                        error = error.localizedMessage ?: "ERROR",
                    )
            }
        }
    }

    fun setInput(field: TxtTocEditorField, text: String, cursor: Int) {
        if (state.value.saving || state.value.finished) return
        revision++
        saved[KEY + field.name] = text
        val position = cursor.coerceIn(0, text.length)
        saved[KEY + "cursor." + field.name] = position
        val cursors =
            state.value.cursors.mapIndexed { index, old ->
                if (index == field.ordinal) position else old
            }
        mutableState.value =
            when (field) {
                TxtTocEditorField.Name -> state.value.copy(name = text, cursors = cursors)
                TxtTocEditorField.Regex -> state.value.copy(regex = text, cursors = cursors)
                TxtTocEditorField.Replacement ->
                    state.value.copy(replacement = text, cursors = cursors)
                TxtTocEditorField.Example -> state.value.copy(example = text, cursors = cursors)
            }
    }

    fun focus(field: TxtTocEditorField) {
        saved[KEY + "focus"] = field.name
        mutableState.value = state.value.copy(focused = field)
    }

    fun currentRule() =
        (original ?: TxtTocRuleSnapshot(draftId)).copy(
            name = state.value.name,
            rule = state.value.regex,
            replacement = state.value.replacement,
            example = state.value.example,
        )

    fun copyJson(): String = GSON.toJson(currentRule().entity())

    fun paste(text: String?) {
        if (state.value.finished || state.value.saving) return
        if (text.isNullOrBlank()) {
            mutableState.value = state.value.copy(error = "剪贴板为空")
            return
        }
        val start = revision
        viewModelScope.launch {
            try {
                val rule =
                    withContext(parseDispatcher) {
                        TxtTocRuleSnapshot.from(GSON.fromJsonObject<TxtTocRule>(text).getOrThrow())
                    }
                coroutineContext.ensureActive()
                if (revision != start || state.value.finished || state.value.saving) return@launch
                TxtTocEditorField.entries.forEach { field ->
                    setInput(
                        field,
                        when (field) {
                            TxtTocEditorField.Name -> rule.name
                            TxtTocEditorField.Regex -> rule.rule
                            TxtTocEditorField.Replacement -> rule.replacement
                            TxtTocEditorField.Example -> rule.example.orEmpty()
                        },
                        0,
                    )
                }
                mutableState.value = state.value.copy(error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision == start && !state.value.finished && !state.value.saving)
                    mutableState.value = state.value.copy(error = "格式不对")
            }
        }
    }

    fun codeRequest(): TxtTocEditorCodeRequest? {
        if (
            state.value.loading ||
                state.value.loadFailed ||
                state.value.saving ||
                state.value.finished ||
                saved.contains(KEY + "pendingField")
        )
            return null
        val field = state.value.focused ?: return null
        saved[KEY + "pendingField"] = field.name
        return TxtTocEditorCodeRequest(
            field,
            state.value[field],
            state.value.cursors[field.ordinal],
        )
    }

    fun codeResult(text: String?, cursor: Int?): Boolean {
        val field =
            saved.get<String>(KEY + "pendingField")?.let(TxtTocEditorField::valueOf) ?: return false
        saved.remove<String>(KEY + "pendingField")
        if (!state.value.saving && !state.value.finished && text != null) {
            setInput(field, text, cursor ?: 0)
            focus(field)
        }
        return true
    }

    fun codeCancelled() {
        saved.remove<String>(KEY + "pendingField")
    }

    fun save() {
        if (
            state.value.loading ||
                state.value.loadFailed ||
                state.value.saving ||
                state.value.finished
        )
            return
        val rule = currentRule()
        mutableState.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                withContext(parseDispatcher) {
                    require(rule.name.isNotEmpty()) { "名称不能为空" }
                    try {
                        Pattern.compile(rule.rule, Pattern.MULTILINE)
                    } catch (error: PatternSyntaxException) {
                        throw IllegalArgumentException(
                            "正则语法错误或不支持(txt)：${error.localizedMessage}",
                            error,
                        )
                    }
                }
                val persisted = repository.save(rule, requireExisting = initialId != null)
                coroutineContext.ensureActive()
                markLoaded(persisted)
                saved[KEY + "pendingCallback"] = true
                mutableState.value = state.value.copy(saving = false, pendingCallback = true)
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    state.value.copy(saving = false, error = error.localizedMessage ?: "ERROR")
            }
        }
    }

    fun consumeSavedRule(): TxtTocRuleSnapshot? {
        if (!state.value.pendingCallback) return null
        saved[KEY + "pendingCallback"] = false
        mutableState.value = state.value.copy(pendingCallback = false)
        return currentRule()
    }

    fun requestClose() {
        if (state.value.saving || state.value.finished) return
        val current = currentRule()
        val original = this.original
        if (
            current.name == original?.name.orEmpty() &&
                current.rule == original?.rule.orEmpty() &&
                current.replacement == original?.replacement.orEmpty() &&
                current.example.orEmpty() == original?.example.orEmpty()
        )
            finish()
        else {
            saved[KEY + "confirmExit"] = true
            mutableState.value = state.value.copy(confirmExit = true)
        }
    }

    fun keepEditing() {
        saved[KEY + "confirmExit"] = false
        mutableState.value = state.value.copy(confirmExit = false)
    }

    fun discard() {
        if (!state.value.saving) finish()
    }

    private fun finish() {
        codeCancelled()
        saved[KEY + "finished"] = true
        saved[KEY + "confirmExit"] = false
        mutableState.value = state.value.copy(finished = true, confirmExit = false)
    }
}
