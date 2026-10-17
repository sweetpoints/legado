package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Issue1008MenuContractTest {

    @Test
    fun sourceIconsAndRssOverflowFollowReportedMenuContract() {
        val manager = sequenceOf(
            File("../flutter/apps/legado/lib/embedded/source_manager"),
            File("flutter/apps/legado/lib/embedded/source_manager"),
        ).first { it.isDirectory }
        val source = manager.walkTopDown().filter { it.extension == "dart" }
            .map(File::readText).joinToString("\n")
        // The existing manager route now renders Flutter, so preserve the actual
        // reported menu contract against its production actions and domain icon.
        assertTrue("Online import remains reachable in the manager menu",
            source.contains("'online'") && source.contains("source-manager-action:"))
        assertTrue("Domain grouping remains an actual manager action",
            source.contains("'domain'"))
        assertTrue("Domain grouping retains a recognizable public/domain icon",
            source.contains("Icons.public"))

        // RSS overflow behavior remains covered by RssReaderScreenTest using
        // the real Compose menu; changing source manager does not remove it.
    }
}
