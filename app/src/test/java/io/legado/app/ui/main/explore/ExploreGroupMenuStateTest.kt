package io.legado.app.ui.main.explore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreGroupMenuStateTest {

    @Test
    fun `group query parsing preserves exact group names`() {
        assertNull(exploreGroupFromQuery(null))
        assertNull(exploreGroupFromQuery("keyword"))
        assertEquals("", exploreGroupFromQuery("group:"))
        assertEquals("all", exploreGroupFromQuery("group:all"))
        assertEquals("manage", exploreGroupFromQuery("group:manage"))

        assertTrue(isExploreAllQuery(null))
        assertTrue(isExploreAllQuery("keyword"))
        assertFalse(isExploreAllQuery("group:"))
        assertFalse(isExploreAllQuery("group:missing"))
    }

    @Test
    fun `only existing groups remain selected`() {
        val groups = linkedSetOf("all", "manage", "novel")

        assertNull(selectedExploreGroup("keyword", groups))
        assertNull(selectedExploreGroup("group:missing", groups))
        assertEquals("all", selectedExploreGroup("group:all", groups))
        assertEquals("manage", selectedExploreGroup("group:manage", groups))
        assertEquals("novel", selectedExploreGroup("group:novel", groups))
    }

    @Test
    fun `Compose group controls retain all and exact group selection`() {
        val source =
            readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreHomeScreen.kt")
        assertTrue(source.contains("R.string.all_source"))
        assertTrue(source.contains("isExploreAllQuery(state.query)"))
        assertTrue(source.contains("selectedExploreGroup(state.query, state.groups.toSet())"))
        val log = readProjectFile("src/main/assets/updateLog.md")
        assertTrue(log.contains("发现页书源分组菜单增加全部书源选项和当前分组勾选"))
    }

    @Test
    fun `discover toolbar opens book source management`() {
        val fragment =
            readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt")
        val screen =
            readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreHomeScreen.kt")
        assertTrue(fragment.contains("startActivity<BookSourceActivity>()"))
        assertTrue(screen.contains("Icons.Default.Settings"))
        assertTrue(screen.contains("R.string.book_source_manage"))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path")).firstOrNull(File::isFile)?.readText().orEmpty()
}
