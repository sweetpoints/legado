package io.legado.app.ui.book.info.detail

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailPrompt
import io.legado.app.data.repository.BookDetailPromptKind
import io.legado.app.data.repository.BookDetailWebFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookDetailPromptScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val localBook =
        BookDetailBook.from(
            Book(bookUrl = "local", origin = "local", type = BookType.text or BookType.local)
        )

    @Test
    fun deleteCheckboxLabelsToggleExactlyOnceAndBusyDisablesConfirmation() {
        val originalChoices = mutableListOf<Boolean>()
        val remoteChoices = mutableListOf<Boolean>()
        var enabled by mutableStateOf(true)
        compose.setContent {
            var preferences by remember {
                mutableStateOf(BookDetailPreferences(true, false, false, true))
            }
            var prompt by remember { mutableStateOf(BookDetailPrompt(BookDetailPromptKind.Delete)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                BookDetailPromptScreen(
                    prompt,
                    localBook,
                    emptyList(),
                    preferences,
                    enabled,
                    BookDetailPromptActions(
                        {},
                        { _, _ -> },
                        { checked ->
                            originalChoices += checked
                            preferences = preferences.copy(deleteOriginal = checked)
                        },
                        { current, checked ->
                            remoteChoices += checked
                            prompt = current.copy(deleteRemote = checked)
                        },
                        {},
                    ),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.delete_book_file)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete_webdav_book_file)).performClick()
        assertEquals(listOf(true), originalChoices)
        assertEquals(listOf(true), remoteChoices)
        compose.onNodeWithText(context.getString(R.string.delete_book_file)).assertIsOn()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("book-detail-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.delete_book_file)).assertIsNotEnabled()
    }

    @Test
    fun webFileListScrollsToActualSelectedIndexAndUploadChoiceRetainsItsState() {
        val selections = mutableListOf<Int?>()
        val uploadChoices = mutableListOf<Boolean>()
        val prompt = BookDetailPrompt(BookDetailPromptKind.WebFiles, readAfter = true)
        val files = List(30) { index -> BookDetailWebFile("file-$index", "Book $index.txt") }
        compose.setContent {
            var preferences by remember {
                mutableStateOf(BookDetailPreferences(true, false, false, true))
            }
            MaterialTheme {
                BookDetailPromptScreen(
                    prompt,
                    localBook,
                    files,
                    preferences,
                    true,
                    BookDetailPromptActions(
                        {},
                        { selected, index ->
                            assertEquals(prompt, selected)
                            selections += index
                        },
                        {},
                        { _, _ -> },
                        { checked ->
                            uploadChoices += checked
                            preferences = preferences.copy(uploadImported = checked)
                        },
                    ),
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.upload_imported_book_to_webdav))
            .performClick()
        compose
            .onNodeWithTag("book-detail-file-24")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(24), selections)
        assertEquals(listOf(true), uploadChoices)
    }

    @Test
    fun archiveSelectionUsesActualIndexWhenVisibleLabelsAreRepeated() {
        val selections = mutableListOf<Int?>()
        val prompt =
            BookDetailPrompt(
                BookDetailPromptKind.ArchiveEntries,
                values = List(30) { "duplicate.txt" },
            )
        compose.setContent {
            MaterialTheme {
                BookDetailPromptScreen(
                    prompt,
                    localBook,
                    emptyList(),
                    BookDetailPreferences(true, false, false, false),
                    true,
                    BookDetailPromptActions(
                        {},
                        { _, index -> selections += index },
                        {},
                        { _, _ -> },
                        {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("book-detail-archive-24").performScrollTo().performClick()
        assertEquals(listOf(24), selections)
    }

    @Test
    fun overwriteConfirmAndDismissStayDistinctAndPreserveReadContinuationPayload() {
        val confirmed = mutableListOf<BookDetailPrompt>()
        val dismissed = mutableListOf<BookDetailPrompt>()
        val prompt = BookDetailPrompt(BookDetailPromptKind.OverwriteUpload, readAfter = true)
        compose.setContent {
            MaterialTheme {
                BookDetailPromptScreen(
                    prompt,
                    localBook,
                    emptyList(),
                    BookDetailPreferences(true, false, false, true),
                    true,
                    BookDetailPromptActions(
                        { dismissed += it },
                        { selected, _ -> confirmed += selected },
                        {},
                        { _, _ -> },
                        {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("book-detail-confirm").performClick()
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        assertEquals(listOf(prompt), confirmed)
        assertEquals(listOf(prompt), dismissed)
    }
}
