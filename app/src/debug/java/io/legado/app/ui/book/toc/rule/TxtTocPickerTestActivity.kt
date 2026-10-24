package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.widget.FrameLayout
import io.legado.app.base.BaseThemedActivity

/** Debug-only real Activity callback host. Absent from the Release APK and normal navigation. */
class TxtTocPickerTestActivity : BaseThemedActivity(), TxtTocRuleDialog.CallBack {
    val receipts = mutableListOf<String>()
    override fun createContentView() { setContentView(FrameLayout(this)) }
    override fun onActivityCreated(savedInstanceState: Bundle?) = Unit
    override fun onTocRegexDialogResult(tocRegex: String) { receipts += tocRegex }
}
