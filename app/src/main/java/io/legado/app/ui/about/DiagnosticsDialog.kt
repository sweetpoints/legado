package io.legado.app.ui.about

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.utils.setLayout
import java.util.UUID

/** Only bounded display projections cross this boundary. Detail bodies and file identities stay native. */
abstract class DiagnosticsDialog : BaseDialogFragment(0) {
    internal abstract val crash: Boolean
    internal abstract fun rows(): List<DiagnosticsRow>
    internal abstract fun busy(): Boolean
    internal abstract fun failed(): Boolean
    internal abstract fun listEstablished(): Boolean
    internal abstract fun notice(): String?
    internal abstract fun confirmation(): Boolean
    internal abstract fun act(op: String, id: String?)
    internal abstract fun rememberScroll(index: Int, offset: Double, anchor: String? = null)
    internal abstract fun scrollAnchor(): String?
    internal abstract fun scroll(): Pair<Int, Double>
    internal open fun openLink(id: String, index: Int) { error("Links unavailable") }
    internal var page = 0
    private var binding = ""
    private var sequence = 0L
    private var revision = 0L
    private val viewportEpoch = DiagnosticsViewportEpoch()
    private var tokens = emptyMap<String, String>()
    private val identities = mutableMapOf<String, String>()
    internal fun displayIds(): List<String> = tokens.keys.toList()
    internal fun acceptsDisplayId(id: String): Boolean = tokens.containsKey(id)
    internal fun bind(): String {
        binding = UUID.randomUUID().toString(); sequence = 0; return binding
    }
    internal fun snapshot(owner: String): Map<String, Any?> {
        require(owner == binding && isAdded)
        val all = rows()
        val savedScroll = scroll()
        val viewport = DiagnosticsViewport.project(all.map { it.id },
            DiagnosticsViewportCheckpoint(page, savedScroll.first, savedScroll.second, scrollAnchor()), !busy() && !failed() && listEstablished())
        val pages = viewport.pages
        page = viewport.checkpoint.page
        if (!busy() && !failed() && listEstablished()) rememberScroll(viewport.checkpoint.index, viewport.checkpoint.offset, viewport.checkpoint.anchor)
        val visible = all.drop(viewport.page * 100).take(100)
        identities.keys.retainAll(all.map { it.id }.toSet())
        tokens = visible.associate { row -> identities.getOrPut(row.id) { UUID.randomUUID().toString() } to row.id }
        val (index, offset) = scroll()
        return mapOf("schemaVersion" to 1, "binding" to owner, "revision" to ++revision, "sequence" to sequence, "viewportEpoch" to viewportEpoch.value,
            "listEstablished" to listEstablished(), "busy" to busy(), "error" to failed(), "notice" to notice(), "confirmClear" to confirmation(),
            "page" to viewport.page, "pages" to pages, "scrollIndex" to index, "scrollOffset" to offset,
            "rows" to visible.map { row -> mapOf("id" to identities.getValue(row.id), "time" to row.time.take(80),
                "text" to row.text.take(512), "links" to row.links.map { it.take(120) }, "details" to row.details, "truncated" to (row.text.length > 512)) })
    }
    internal fun command(owner: String, raw: Any?): Map<String, Any?> {
        val args = raw as? Map<*, *> ?: error("Invalid command")
        require(owner == binding && args["binding"] == owner && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val next = (args["sequence"] as? Number)?.toLong() ?: error("Missing sequence")
        require(next == sequence + 1); sequence = next
        val op = args["op"] as? String ?: error("Missing operation")
        require(!confirmation() || op in setOf("scroll", "back", "cancelClear", "confirmClear"))
        when (op) {
            "scroll" -> {
                require(args.keys == setOf("binding", "sequence", "op", "index", "offset", "id", "viewportEpoch"))
                val receiptEpoch = (args["viewportEpoch"] as Number).toLong()
                // Stale internal receipts are consumed silently; they are never replayed.
                if (!viewportEpoch.accepts(receiptEpoch)) return snapshot(owner)
                require(!busy() && !failed() && listEstablished())
                val index = (args["index"] as Number).toInt()
                val offset = (args["offset"] as Number).toDouble()
                require(offset.isFinite() && offset >= 0 && offset <= 10_000_000)
                val token = args["id"] as? String
                val all = rows()
                val nativeId = if (all.isEmpty()) { require(index == 0 && token == null); null }
                    else identities.entries.singleOrNull { it.value == token }?.key ?: error("Retired viewport anchor")
                val globalIndex = nativeId?.let { id -> all.indexOfFirst { it.id == id }.also { require(it >= 0) } }
                require(index in 0..99)
                val followHead = page == 0 && index == 0 && offset == 0.0
                val anchor = if (followHead) null else nativeId?.let(DiagnosticsViewport::anchor)
                if (!followHead && globalIndex != null) page = globalIndex / 100
                rememberScroll(if (followHead) 0 else globalIndex?.rem(100) ?: 0, offset, anchor)
            }
            "previous", "next" -> {
                require(args.keys == setOf("binding", "sequence", "op") && !busy())
                viewportEpoch.retire()
                page = DiagnosticsViewport.move(page, rows().size, if (op == "next") 1 else -1)
                rememberScroll(0, 0.0)
            }
            "link" -> {
                require(args.keys == setOf("binding", "sequence", "op", "id", "index") && !busy())
                openLink(tokens[args["id"]] ?: error("Retired log"), (args["index"] as Number).toInt())
            }
            "open" -> {
                require(args.keys == setOf("binding", "sequence", "op", "id") && !busy())
                val id = tokens[args["id"]] ?: error("Retired log")
                act(op, id)
            }
            else -> {
                require(args.keys == setOf("binding", "sequence", "op"))
                if (op in setOf("clear", "confirmClear", "retry")) viewportEpoch.retire()
                act(op, null)
            }
        }
        return snapshot(owner)
    }
    internal fun release(owner: String) { if (binding == owner) binding = "" }
    internal fun closePage() {
        viewportEpoch.retire()
        if (confirmation()) act("cancelClear", null) else dismissAllowingStateLoss()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false
        page = savedInstanceState?.getInt("diagnostics.page") ?: 0
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("diagnostics.page", page); super.onSaveInstanceState(outState) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = if (crash) R.id.crash_logs_flutter_content else R.id.app_logs_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.findFragmentByTag(TAG) == null) childFragmentManager.beginTransaction()
            .add(view.id, if (crash) CrashLogsFlutterFragment() else AppLogsFlutterFragment(), TAG).commitNow()
    }
    override fun onStart() {
        super.onStart(); setLayout(0.9f, (resources.displayMetrics.heightPixels * 0.8).toInt())
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) requestBack(); true } else false
        }
    }
    override fun onResume() { super.onResume(); view?.post { if (isResumed) (childFragmentManager.findFragmentByTag(TAG) as? DiagnosticsFlutterFragment)?.onPostResume() } }
    private fun requestBack() {
        val fragment = childFragmentManager.findFragmentByTag(TAG) as? DiagnosticsFlutterFragment
        if(fragment?.flutterEngine?.dartExecutor?.isExecutingDart == true) fragment.onBackPressed()
        else closePage()
    }
    override fun dismiss() = requestBack()
    private companion object { const val TAG = "diagnostics-flutter" }
}
internal data class DiagnosticsRow(val id: String, val time: String, val text: String, val details: Boolean, val links: List<String> = emptyList())
