package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipTemplateSlot
import io.legado.app.help.config.ReaderInfoTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TipConfigDialogThemeTest {
    @Test fun everyPlaceholderCanBeInsertedWithoutAnAndroidViewContext() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        ReaderInfoTemplate.placeholders.forEach { placeholder ->
            model.openTemplate(TipTemplateSlot.HeaderLeft)
            model.editTemplate("", 0, 0)
            model.insertPlaceholder(placeholder)
            assertEquals(placeholder, model.state.value.template?.text)
            model.dismissEditors()
        }
        assertTrue(repository.templatesWritten.isEmpty())
    }
}
