package io.legado.app.ui.highlight

import android.content.Context
import androidx.lifecycle.Lifecycle
import io.flutter.plugin.common.*
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each UI engine has an independent binding; native Room/session/effect owners outlive it. */
internal class HighlightPresentationChannelBridge(
    private val context: Context,
    messenger: BinaryMessenger,
    private val manager: HighlightRuleActivity?,
    private val groups: HighlightGroupManageDialog?,
) : AutoCloseable {
    private val groupMode = groups != null
    private val path = if (groupMode) "highlight_groups" else "highlight_manager"
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/$path")
    private val events = EventChannel(messenger, "legado/$path/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val binding = UUID.randomUUID().toString()
    private val commands = manager?.let { HighlightManagerCommands(it.viewModel) }
    private var revision = 0L
    private var sequence = 0L
    private var rejected = false
    private var closed = false
    private var observation: Job? = null
    private fun resumed() = if (groupMode) groups!!.isResumed && !groups.parentFragmentManager.isStateSaved
        else manager!!.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !manager.supportFragmentManager.isStateSaved
    private val labels = mapOf(
        "title" to if (groupMode) R.string.highlight_rule_group_manage else R.string.highlight_rule,
        "back" to R.string.back, "add" to R.string.highlight_rule_add, "more" to R.string.menu,
        "import" to R.string.import_local, "exportAll" to R.string.export_all,
        "groups" to R.string.highlight_rule_group_manage, "filter" to R.string.highlight_rule_group_filter,
        "all" to R.string.select_all, "allGroups" to R.string.all, "invert" to R.string.revert_selection,
        "delete" to R.string.delete, "enableSelection" to R.string.enable_selection,
        "disableSelection" to R.string.disable_selection, "topSelection" to R.string.selection_to_top,
        "bottomSelection" to R.string.selection_to_bottom, "export" to R.string.export_selection,
        "share" to R.string.share_selected_source, "retry" to R.string.retry, "edit" to R.string.edit,
        "top" to R.string.to_top, "bottom" to R.string.to_bottom, "up" to R.string.dictionary_move_up,
        "down" to R.string.dictionary_move_down, "empty" to R.string.highlight_rule_empty,
        "noGroup" to R.string.no_group, "cancel" to R.string.cancel, "confirm" to R.string.ok,
        "sureDelete" to R.string.sure_del, "exportSuccess" to R.string.export_success,
        "close" to R.string.close, "groupEdit" to R.string.group_edit, "groupName" to R.string.group_name,
        "groupDelete" to R.string.highlight_rule_group_delete, "groupDeleteMessage" to R.string.highlight_rule_group_delete_message,
        "moveGroup" to R.string.move_to_group,
    ).mapValues { context.getString(it.value) }
    private fun snapshot(): Map<String, Any?> {
        val raw = if (groupMode) highlightGroupsSnapshot(groups!!.model.state.value, binding, ++revision)
            else highlightManagerSnapshot(manager!!.viewModel.state.value, binding, ++revision)
        val summary = manager?.viewModel?.state?.value?.draft?.exportResult?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) io.legado.app.help.DirectLinkUpload.getSummary() else null
        }
        return raw + mapOf("labels" to labels, "exportSummary" to summary)
    }
    init {
        ui.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "bootstrap" -> mapOf("route" to if (groupMode) "/highlight-groups" else "/highlight-manager",
                    "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                    "theme" to when (AppConfig.themeMode) { "1", "3" -> "light"; "2" -> "dark"; else -> "system" })
                "navigation.close" -> gate.withLock {
                    require(resumed())
                    if (groupMode) { val host = requireNotNull(groups); highlightGroupsCommand(host.model, "back", emptyMap<String, Any?>(), host::finishGroups); host.model.flush() }
                    else commands!!.execute("back", emptyMap<String, Any?>())
                    null
                }
                else -> error("Unknown bootstrap request")
            }
        } }
        methods.setMethodCallHandler { call, result -> reply(result) {
            gate.withLock {
                when (call.method) {
                    "snapshot" -> snapshot()
                    "command" -> {
                        val a = call.arguments as? Map<*, *> ?: error("Invalid command")
                        require(a["binding"] == binding)
                        val next = (a["sequence"] as Number).toLong(); require(next > sequence); sequence = next
                        val op = a["op"] as String
                        require(resumed() || op == "cancelGesture")
                        require(!rejected || op in setOf("name", "cancel", "back"))
                        try {
                            if (groupMode) { val host = requireNotNull(groups); highlightGroupsCommand(host.model, op, a, host::finishGroups); host.model.flush() }
                            else commands!!.execute(op, a)
                            if (op == "name") {
                                val s = groups!!.model.state.value
                                require(s.name == a["text"] && s.nameStart == (a["start"] as Number).toInt() && s.nameEnd == (a["end"] as Number).toInt())
                                rejected = false
                            }
                            if (op in setOf("cancel", "back") && groups?.model?.state?.value?.stage == HighlightGroupStage.None) rejected = false
                            snapshot()
                        } catch (error: Exception) { if (op == "name") rejected = true; throw error }
                    }
                    else -> error("Unknown method")
                }
            }
        } }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                observation?.cancel()
                val flow: Flow<Unit> = if (groupMode) groups!!.model.state.map { } else manager!!.viewModel.state.map { }
                observation = scope.launch { flow.collect { if (!closed) sink.success(snapshot()) } }
            }
            override fun onCancel(arguments: Any?) { observation?.cancel(); observation = null }
        })
    }
    private fun reply(result: MethodChannel.Result, action: suspend () -> Any?) {
        if (closed) { result.error("closed", "Highlight view closed", null); return }
        scope.launch {
            try { result.success(action()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { result.error("invalid_request", "Highlight operation unavailable or invalid", null) }
        }
    }
    override fun close() {
        if (closed) return
        closed = true; observation?.cancel(); manager?.viewModel?.cancelGesture()
        ui.setMethodCallHandler(null); methods.setMethodCallHandler(null); events.setStreamHandler(null); scope.cancel()
    }
}
