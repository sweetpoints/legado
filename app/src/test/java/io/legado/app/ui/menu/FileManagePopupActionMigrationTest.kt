package io.legado.app.ui.menu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class FileManagePopupActionMigrationTest {
    // Actual menu/delete and parent long-press behavior is covered by FileManagementUiTest.
    @Test
    fun `legacy file deletion menu resource is removed`() {
        assertFalse(
            sequenceOf(
                    File("src/main/res/menu/file_long_click.xml"),
                    File("app/src/main/res/menu/file_long_click.xml"),
                )
                .any { it.isFile }
        )
    }
}
