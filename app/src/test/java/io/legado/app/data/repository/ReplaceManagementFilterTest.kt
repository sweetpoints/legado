package io.legado.app.data.repository

import org.junit.Test
import org.junit.Assert.*

class ReplaceManagementFilterTest {
    @Test fun localizedSpecialQueriesPreserveExactOriginalBranchOrder() {
        assertEquals(ReplaceManagementFilter.All, replaceManagementFilter("", "Enabled", "Disabled", "No group"))
        assertEquals(ReplaceManagementFilter.Enabled, replaceManagementFilter("启用", "启用", "禁用", "未分组"))
        assertEquals(ReplaceManagementFilter.Disabled, replaceManagementFilter("Disabled", "Enabled", "Disabled", "No group"))
        assertEquals(ReplaceManagementFilter.NoGroup, replaceManagementFilter("No group", "Enabled", "Disabled", "No group"))
    }
    @Test fun explicitGroupAndFreeSearchKeepCaseWhitespaceAndWildcardInputs() {
        assertEquals(ReplaceManagementFilter.Group(" A,甲 "), replaceManagementFilter("group: A,甲 ", "Enabled", "Disabled", "No group"))
        assertEquals(ReplaceManagementFilter.Search("Group:A"), replaceManagementFilter("Group:A", "Enabled", "Disabled", "No group"))
        assertEquals(ReplaceManagementFilter.Search("%x_"), replaceManagementFilter("%x_", "Enabled", "Disabled", "No group"))
        assertEquals(ReplaceManagementFilter.Search(" "), replaceManagementFilter(" ", "Enabled", "Disabled", "No group"))
    }
}
