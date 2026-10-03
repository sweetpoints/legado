package io.legado.app.ui.book.info.edit

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookInfoEditLayoutTest {

    @Test
    fun `Chinese local image actions use compact labels`() {
        assertEquals("本地图片", stringValue("app/src/main/res/values-zh/strings.xml"))
        assertEquals("本機圖片", stringValue("app/src/main/res/values-zh-rTW/strings.xml"))
        assertEquals("本地圖片", stringValue("app/src/main/res/values-zh-rHK/strings.xml"))
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))

    private fun stringValue(path: String): String {
        val nodes = parse(path).getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == "select_local_image" }
            .textContent
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

}
