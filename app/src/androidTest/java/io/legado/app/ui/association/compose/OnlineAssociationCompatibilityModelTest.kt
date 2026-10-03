package io.legado.app.ui.association.compose

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.FileAssociationSessionRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAssociationCompatibilityModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun getTextRetainsUtf8HeaderAndMainCallbackWithoutSavingPayload() = runBlocking {
        val userAgent = AtomicReference<String?>()
        val server =
            object : NanoHTTPD(0) {
                override fun serve(session: IHTTPSession): Response {
                    userAgent.set(session.headers["user-agent"])
                    return newFixedLengthResponse("原始文本")
                }
            }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val fixture = fixture()
        val delivered = AtomicReference<String?>()
        try {
            instrumentation.runOnMainSync {
                fixture.model.getText(
                    "http://127.0.0.1:${server.listeningPort}/text#requestWithoutUA"
                ) { text ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    delivered.set(text)
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (delivered.get() == null) delay(10) }
            }
            assertEquals("原始文本", delivered.get())
            assertEquals("null", userAgent.get())
            instrumentation.runOnMainSync {
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), fixture.saved.keys())
                assertEquals(
                    fixture.ticket,
                    fixture.saved.get<String>(AssociationImportViewModel.TICKET_KEY),
                )
            }
        } finally {
            fixture.close()
            server.stop()
        }
    }

    @Test
    fun clearedOwnerNeverInvokesTextCallbackFromLateRealHttpResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server =
            object : NanoHTTPD(0) {
                override fun serve(session: IHTTPSession): Response {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return newFixedLengthResponse("late response")
                }
            }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val fixture = fixture()
        val deliveries = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                fixture.model.getText("http://127.0.0.1:${server.listeningPort}/late") {
                    deliveries.incrementAndGet()
                }
            }
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            instrumentation.runOnMainSync { clear(fixture.model) }
            release.countDown()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { fixture.model.viewModelScope.coroutineContext.job.join() }
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, deliveries.get())
        } finally {
            release.countDown()
            fixture.close()
            server.stop()
        }
    }

    @Test
    fun closedOwnerWithoutStoreClearRejectsLateHttpSuccessAndDecodeFailure() = runBlocking {
        for (invalidGzip in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server =
                object : NanoHTTPD(0) {
                    override fun serve(session: IHTTPSession): Response {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return newFixedLengthResponse("late response").also {
                            if (invalidGzip) it.addHeader("Content-Encoding", "gzip")
                        }
                    }
                }
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val fixture = fixture()
            val deliveries = AtomicInteger()
            try {
                instrumentation.runOnMainSync {
                    fixture.model.getText("http://127.0.0.1:${server.listeningPort}/late") {
                        deliveries.incrementAndGet()
                    }
                }
                assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
                val requests = fixture.model.viewModelScope.coroutineContext.job.children.toList()
                withContext(Dispatchers.Main) { fixture.model.closeOwnedSession() }
                release.countDown()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { requests.joinAll() }
                }
                instrumentation.waitForIdleSync()
                assertEquals(0, deliveries.get())
                instrumentation.runOnMainSync { assertEquals(null, fixture.model.errorLive.value) }
            } finally {
                release.countDown()
                fixture.close()
                server.stop()
            }
        }
    }

    private suspend fun fixture(): Fixture {
        val sessions = FileAssociationSessionRepository(application)
        val ticket =
            sessions.create(AssociationInput(AssociationHostKind.Online, AssociationInputKind.View))
        val initial = sessions.read(ticket)
        sessions.write(
            ticket,
            initial.copy(revision = initial.revision + 1, phase = AssociationPhase.Preview),
        )
        val saved = SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to ticket))
        lateinit var model: OnlineAssociationCompatibilityModel
        instrumentation.runOnMainSync {
            model = OnlineAssociationCompatibilityModel(application, saved)
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!model.state.value.loaded) delay(10) }
        }
        return Fixture(ticket, saved, sessions, model)
    }

    private fun clear(model: OnlineAssociationCompatibilityModel) {
        ViewModelStore().apply {
            put("model", model)
            clear()
        }
    }

    private inner class Fixture(
        val ticket: String,
        val saved: SavedStateHandle,
        val sessions: FileAssociationSessionRepository,
        val model: OnlineAssociationCompatibilityModel,
    ) {
        suspend fun close() {
            instrumentation.runOnMainSync { clear(model) }
            sessions.release(ticket)
        }
    }
}
