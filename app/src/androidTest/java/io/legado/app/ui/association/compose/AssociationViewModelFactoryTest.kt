package io.legado.app.ui.association.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.association.AssociationBookPreview
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.FileAssociationSessionRepository
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationViewModelFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun childBeforeHostUsesSameSavedModelAndRecreationNeverSavesLargeIntentDefaults() =
        runBlocking {
            val sessions = FileAssociationSessionRepository(context)
            val largeText = "private body".repeat(100_000)
            val ticket =
                sessions.create(
                    AssociationInput(
                        AssociationHostKind.File,
                        AssociationInputKind.SharedText,
                        text = largeText,
                    )
                )
            val initial = sessions.read(ticket)
            sessions.write(
                ticket,
                initial.copy(
                    revision = 1,
                    phase = AssociationPhase.Preview,
                    previews =
                        listOf(
                            AssociationBookPreview(
                                "book",
                                "file:///private/book",
                                "book.txt",
                                GSON.toJson(
                                    Book(bookUrl = "private", name = "Book", intro = largeText)
                                ),
                            )
                        ),
                    selectedIds = listOf("book"),
                ),
            )
            val scenario =
                ActivityScenario.launch<AssociationFactoryFixtureActivity>(
                    Intent(context, AssociationFactoryFixtureActivity::class.java)
                        .putExtra("prepared", ticket)
                        .putExtra(Intent.EXTRA_TEXT, largeText)
                        .putExtra("arbitraryLargeMetadata", largeText)
                        .setData(Uri.parse("content://provider/" + largeText))
                )
            try {
                lateinit var original: FileAssociationCompatibilityModel
                scenario.onActivity { activity ->
                    original = activity.model
                    assertSame(original, activity.childModel)
                    assertEquals(
                        setOf(AssociationImportViewModel.TICKET_KEY),
                        activity.createdHandle!!.keys(),
                    )
                    assertEquals(
                        ticket,
                        activity.createdHandle!!.get<String>(AssociationImportViewModel.TICKET_KEY),
                    )
                    assertFalse(activity.intent.hasExtra(Intent.EXTRA_TEXT))
                    assertEquals(null, activity.intent.data)
                    val saved = activity.captureSavedState()
                    assertNoLargePayload(saved, largeText)
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeBundle(saved)
                        assertTrue(parcel.dataSize() < 64 * 1024)
                    } finally {
                        parcel.recycle()
                    }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertSame(original, activity.model)
                    assertSame(activity.model, activity.childModel)
                    assertEquals(ticket, activity.intent.getStringExtra("prepared"))
                    assertNoLargePayload(activity.captureSavedState(), largeText)
                }
                assertEquals(largeText, sessions.read(ticket).input.text)
            } finally {
                scenario.close()
                sessions.release(ticket)
            }
        }

    private fun assertNoLargePayload(bundle: Bundle, payload: String) {
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") val value = bundle.get(key)
            if (value is Bundle) assertNoLargePayload(value, payload)
            if (value is String) assertFalse(value.contains(payload))
        }
    }
}
