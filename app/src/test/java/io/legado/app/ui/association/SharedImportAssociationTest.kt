package io.legado.app.ui.association

import io.legado.app.constant.AppPattern.jsFileRegex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportAssociationTest {

    @Test
    fun `shared text extracts exactly one http url for online import`() {
        assertEquals(
            "https://example.com/source.json?group=1",
            extractSharedImportUrl("书源\nhttps://example.com/source.json?group=1"),
        )
        assertEquals(
            "HTTP://example.com/source.json",
            extractSharedImportUrl("HTTP://example.com/source.json"),
        )
        assertEquals(
            "https://例子.测试/source.json",
            extractSharedImportUrl("https://例子.测试/source.json"),
        )
        assertEquals(
            "https://example.com/source.json",
            extractSharedImportUrl("请导入（https://example.com/source.json）。"),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            extractSharedImportUrl("https://en.wikipedia.org/wiki/Function_(mathematics)"),
        )
        assertEquals(
            "https://example.com/source.json",
            extractSharedImportUrl("https://example.com/source.json https://"),
        )
        assertNull(extractSharedImportUrl("ftp://example.com/source.json"))
        assertNull(extractSharedImportUrl("https://one.example/a https://two.example/b"))
        assertNull(extractSharedImportUrl("{\"url\":\"https://example.com/source.json\"}"))
        assertNull(
            extractSharedImportUrl(
                """{"bookSourceUrl":"https://source.example","bookSourceComment":"文档 https://docs.example"}"""
            )
        )
        assertNull(extractSharedImportUrl("https://"))
    }

    @Test
    fun `share import accepts supported json book and archive mime types`() {
        assertTrue(isSupportedSharedImportMimeType("text/plain"))
        assertTrue(isSupportedSharedImportMimeType("text/*"))
        assertTrue(isSupportedSharedImportMimeType("application/json"))
        assertTrue(isSupportedSharedImportMimeType("APPLICATION/JSON"))
        assertFalse(isSupportedSharedImportMimeType("application/javascript"))
        assertTrue(isSupportedSharedImportMimeType("application/octet-stream"))
        assertTrue(isSupportedSharedImportMimeType("application/epub+zip"))
        assertTrue(isSupportedSharedImportMimeType("application/pdf"))
        assertTrue(isSupportedSharedImportMimeType("application/zip"))
        assertTrue(isSupportedSharedImportMimeType("application/x-zip-compressed"))
        assertFalse(isSupportedSharedImportMimeType("image/png"))
        assertFalse(isSupportedSharedImportMimeType(null))
    }

    @Test
    fun `share target keeps search and import declarations separate`() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val shareFilter =
            manifest
                .substringAfter(
                    "<intent-filter android:label=\"@string/receiving_shared_import_label\">"
                )
                .substringBefore("</intent-filter>")
        assertTrue(manifest.contains("android:name=\".receiver.SharedReceiverActivity\""))
        assertTrue(manifest.contains("android:label=\"@string/receiving_shared_label\""))
        assertTrue(manifest.contains("android:label=\"@string/receiving_shared_import_label\""))
        assertTrue(shareFilter.contains("android.intent.action.SEND"))
        assertTrue(shareFilter.contains("android:mimeType=\"text/plain\""))
        assertTrue(shareFilter.contains("android:mimeType=\"application/json\""))
        assertTrue(manifest.contains("android.intent.action.SEND_MULTIPLE"))
        assertFalse(shareFilter.contains("javascript"))
        assertTrue(shareFilter.contains("application/octet-stream"))
    }

    @Test
    fun `view association advertises JavaScript source files`() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val knownMimeFilter =
            manifest
                .substringAfter("<!-- VIEW (Open with) action -->")
                .substringBefore("<!-- Works when an app doesn't know")

        assertTrue(knownMimeFilter.contains("android:mimeType=\"application/javascript\""))
        assertTrue(knownMimeFilter.contains("android:mimeType=\"text/javascript\""))
        assertTrue(knownMimeFilter.contains("android:mimeType=\"application/x-javascript\""))
        assertTrue(jsFileRegex.matches("source.js"))
        assertTrue(jsFileRegex.matches("SOURCE.JS"))
        assertFalse(jsFileRegex.matches("source.js.bak"))
    }

    private fun projectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)?.readText()
            ?: error("Missing project file: $pathInApp")
}
