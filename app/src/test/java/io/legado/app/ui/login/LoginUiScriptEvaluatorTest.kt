package io.legado.app.ui.login

import com.script.rhino.RhinoInterruptError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LoginUiScriptEvaluatorTest {

    @Test
    fun `ordinary script exceptions are reported as failures`() = runBlocking {
        val error = IllegalStateException("broken login UI")
        var reported: Throwable? = null

        val result = evaluateLoginUiScript<String>(
            block = { throw error },
            onFailure = { reported = it },
        )

        assertTrue(result.isFailure)
        assertSame(error, result.exceptionOrNull())
        assertSame(error, reported)
    }

    @Test
    fun `fatal errors are not swallowed`() {
        val error = AssertionError("fatal login UI error")

        val thrown = assertThrows(AssertionError::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw error },
                    onFailure = { fail("Fatal errors must not be reported as script exceptions") },
                )
            }
        }

        assertSame(error, thrown)
    }

    @Test
    fun `cancellation exceptions are not swallowed`() {
        val cancellation = CancellationException("cancel login UI")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw cancellation },
                    onFailure = { fail("Cancellation must not be reported as a script error") },
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `Rhino interruption errors are not swallowed`() {
        val interruption = RhinoInterruptError(CancellationException("cancel Rhino"))

        val thrown = assertThrows(RhinoInterruptError::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = { throw interruption },
                    onFailure = { fail("Rhino interruption must not be reported as a script error") },
                )
            }
        }

        assertSame(interruption, thrown)
    }

    @Test
    fun `cancelled coroutine is rechecked before reporting another error`() {
        var reported = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                evaluateLoginUiScript<Unit>(
                    block = {
                        currentCoroutineContext().cancel(
                            CancellationException("cancel before script error")
                        )
                        throw IllegalStateException("script stopped after cancellation")
                    },
                    onFailure = { reported = true },
                )
            }
        }

        assertFalse(reported)
    }

    @Test
    fun `failed rerender keeps current rows while successful empty layout replaces them`() {
        val currentRows = listOf("typed user", "typed password")
        val failed = resolveLoginUiRender<List<String>>(
            currentRows,
            Result.failure(IllegalStateException("render failed")),
        )
        val emptySuccess = resolveLoginUiRender<List<String>>(
            currentRows,
            Result.success(emptyList()),
        )

        assertFalse(failed.shouldApply)
        assertSame(currentRows, failed.value)
        assertTrue(emptySuccess.shouldApply)
        assertEquals(emptyList<String>(), emptySuccess.value)
    }

    // Stored-only initialization is exercised through real Room/header scripts in SourceLoginRepositoryTest.
}
