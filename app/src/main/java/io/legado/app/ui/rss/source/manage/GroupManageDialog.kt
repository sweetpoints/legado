package io.legado.app.ui.rss.source.manage

import io.legado.app.data.repository.RssSourceGroupRepository
import io.legado.app.ui.group.NamedGroupDialog

class GroupManageDialog : NamedGroupDialog() {
    override val groupKind = "rss"
    override fun repository() = RssSourceGroupRepository()
}
