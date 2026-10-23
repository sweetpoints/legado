package io.legado.app.ui.replace.edit

import io.legado.app.R
import io.legado.app.data.repository.ReplaceEditorField

internal fun ReplaceEditorField.label() =
    when (this) {
        ReplaceEditorField.Name -> R.string.replace_rule_summary
        ReplaceEditorField.Group -> R.string.group
        ReplaceEditorField.Pattern -> R.string.replace_rule
        ReplaceEditorField.Replacement -> R.string.replace_to
        ReplaceEditorField.Scope -> R.string.replace_scope
        ReplaceEditorField.ExcludeScope -> R.string.replace_exclude_scope
        ReplaceEditorField.Timeout -> R.string.timeout_millisecond
        ReplaceEditorField.Sample -> R.string.replace_preview_input
    }
