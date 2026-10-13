package io.legado.app.model.sourceEngine

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class SourceHostFailureTest {
    @Test
    fun actualNativeTransportTypesRoundTripWithoutSerializingMessagesInDetails() {
        val error =
            IllegalStateException("private URL/token", SSLHandshakeException("private TLS peer"))
        val encoded = SourceHostFailure.encode(error)
        assertEquals("network_error", encoded.code)
        assertEquals(setOf("schemaVersion", "exceptionTypes"), encoded.details.keys)
        assertFalse(encoded.details.toString().contains("private"))
        val decoded =
            SourceHostFailure.decode(encoded.code, encoded.message, encoded.details)
                as SourceHostException
        assertEquals(encoded.code, decoded.code)
        assertEquals(
            listOf(IllegalStateException::class.java.name, SSLHandshakeException::class.java.name),
            decoded.exceptionTypes,
        )
        assertEquals("network_error", SourceHostFailure.encode(UnknownHostException("host")).code)
        assertEquals(
            "network_timeout",
            SourceHostFailure.encode(SocketTimeoutException("timeout")).code,
        )
    }

    @Test
    fun unknownIOExceptionAndTransportLookingTextRemainUnknown() {
        assertEquals(
            "HOST_CALL_FAILED",
            SourceHostFailure.encode(IOException("HandshakeException: peer")).code,
        )
        assertEquals(
            "HOST_CALL_FAILED",
            SourceHostFailure.encode(IllegalStateException("SocketException: connection")).code,
        )
    }

    @Test
    fun scriptClassificationCannotBeOverriddenByTransportTextOrCause() {
        val error =
            SourceScriptException(
                "script_error",
                "HandshakeException: peer",
                SSLHandshakeException("peer"),
            )
        val encoded = SourceHostFailure.encode(error)
        assertEquals("script_error", encoded.code)
        assertTrue(
            SourceHostFailure.decode(encoded.code, encoded.message, encoded.details)
                is SourceScriptException
        )
    }

    @Test
    fun boundsCauseCyclesAndRejectsUntrustedMetadataValues() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(2, (SourceHostFailure.encode(first).details["exceptionTypes"] as List<*>).size)
        val decoded =
            SourceHostFailure.decode(
                "HOST_CALL_FAILED",
                "unknown",
                mapOf(
                    "schemaVersion" to 1,
                    "exceptionTypes" to
                        listOf("java.io.IOException", "https://private.invalid/token", 3),
                ),
            ) as SourceHostException
        assertEquals(listOf("java.io.IOException"), decoded.exceptionTypes)
        assertTrue(
            (SourceHostFailure.decode(
                    "unknown",
                    null,
                    mapOf("schemaVersion" to 2, "exceptionTypes" to listOf("java.io.IOException")),
                ) as SourceHostException)
                .exceptionTypes
                .isEmpty()
        )
    }
}
