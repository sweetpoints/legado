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
    internal abstract fun notice(): String?
    internal abstract fun confirmation(): Boolean
    internal abstract fun act(op: String, id: String?)
    internal abstract fun rememberScroll(index: Int, offset: Double)
    internal abstract fun scroll(): Pair<Int, Double>
    internal open fun openLink(id: String, index: Int) { error("Links unavailable") }
    internal var page = 0
    private var binding = ""
    private var sequence = 0L
    private var revision = 0L
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
        val pages = maxOf(1, (all.size + 99) / 100)
        page = page.coerceIn(0, pages - 1)
        val visible = all.drop(page * 100).take(100)
        identities.keys.retainAll(all.map { it.id }.toSet())
        tokens = visible.associate { row -> identities.getOrPut(row.id) { UUID.randomUUID().toString() } to row.id }
        val (index, offset) = scroll()
        return mapOf("schemaVersion" to 1, "binding" to owner, "revision" to ++revision, "sequence" to sequence,
            "busy" to busy(), "error" to failed(), "notice" to notice(), "confirmClear" to confirmation(),
            "page" to page, "pages" to pages, "scrollIndex" to index, "scrollOffset" to offset,
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
                require(args.keys == setOf("binding", "sequence", "op", "index", "offset"))
                rememberScroll((args["index"] as Number).toInt(), (args["offset"] as Number).toDouble())
            }
            "previous", "next" -> {
                require(args.keys == setOf("binding", "sequence", "op") && !busy())
                page += if (op == "next") 1 else -1; rememberScroll(0, 0.0)
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
                act(op, null)
            }
        }
        return snapshot(owner)
    }
    internal fun release(owner: String) { if (binding == owner) binding = "" }
    internal fun closePage() {
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
