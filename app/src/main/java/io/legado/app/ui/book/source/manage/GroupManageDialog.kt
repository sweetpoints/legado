package io.legado.app.ui.book.source.manage

import io.legado.app.data.repository.BookSourceGroupRepository
import io.legado.app.ui.group.NamedGroupDialog

class GroupManageDialog : NamedGroupDialog() {
    override val groupKind = "book"
    override fun repository() = BookSourceGroupRepository()
}
