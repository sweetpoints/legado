package io.legado.app.ui.replace

import io.legado.app.data.repository.ReplaceRuleGroupRepository
import io.legado.app.ui.group.NamedGroupDialog

class GroupManageDialog : NamedGroupDialog() {
    override val groupKind = "replace"
    override fun repository() = ReplaceRuleGroupRepository()
}
