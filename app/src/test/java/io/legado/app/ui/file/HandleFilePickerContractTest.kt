package io.legado.app.ui.file

import io.legado.app.data.repository.handleFileMimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class HandleFilePickerContractTest {
    @Test
    fun systemPickerAdvertisesBothCommonJavaScriptMimeTypes() {
        assertEquals(
            listOf("application/javascript", "text/javascript"),
            handleFileMimeTypes(listOf("js")) { null },
        )
    }
}
