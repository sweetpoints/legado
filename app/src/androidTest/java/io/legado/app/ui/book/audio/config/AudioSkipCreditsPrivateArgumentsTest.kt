package io.legado.app.ui.book.audio.config

import io.legado.app.data.entities.Book
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioSkipCreditsPrivateArgumentsTest {
    @Test
    fun largeBookIdentityNeverEntersFragmentArguments() {
        val book = Book(bookUrl = "https://audio/" + "large/".repeat(100000))
        val arguments = requireNotNull(AudioSkipCredits.newInstance(book).arguments)
        assertEquals(setOf(AudioSkipCredits.SESSION_KEY), arguments.keySet())
        assertFalse(arguments.containsKey("bookUrl"))
        val ticket = requireNotNull(arguments.getString(AudioSkipCredits.SESSION_KEY))
        assertEquals(ticket, UUID.fromString(ticket).toString())
    }
}
