package io.legado.app.ui.widget.dialog

import com.google.gson.JsonParser
import io.legado.app.ui.widget.dialog.urloption.UrlOptionDraft
import io.legado.app.ui.widget.dialog.urloption.UrlOptionField
import org.junit.Assert.*
import org.junit.Test

class UrlOptionDialogContractTest {
    @Test
    fun requestAndResponseScriptsRemainSeparate() {
        val json =
            JsonParser.parseString(
                    UrlOptionDraft()
                        .update(UrlOptionField.Js, "request()")
                        .update(UrlOptionField.BodyJs, "response()")
                        .update(UrlOptionField.WebJs, "web()")
                        .toJson()
                )
                .asJsonObject
        assertEquals("request()", json["js"].asString)
        assertEquals("response()", json["bodyJs"].asString)
        assertEquals("web()", json["webJs"].asString)
    }

    @Test
    fun blankOptionsAndDisabledWebViewAreOmitted() {
        assertEquals("{}", UrlOptionDraft().toJson())
        assertEquals(
            "{}",
            UrlOptionDraft()
                .update(UrlOptionField.Method, "  ")
                .update(UrlOptionField.Js, "\n")
                .toJson(),
        )
    }

    @Test
    fun headersAndBodyObjectsKeepTheirJsonTypes() {
        val json =
            JsonParser.parseString(
                    UrlOptionDraft()
                        .update(UrlOptionField.Headers, "{\"X-Test\":\"token\"}")
                        .update(UrlOptionField.Body, "{\"enabled\":true}")
                        .toJson()
                )
                .asJsonObject
        assertEquals("token", json["headers"].asJsonObject["X-Test"].asString)
        assertTrue(json["body"].asJsonObject["enabled"].asBoolean)
    }

    @Test
    fun arrayBodyAndPlainTextUseExistingParser() {
        val array =
            JsonParser.parseString(
                    UrlOptionDraft().update(UrlOptionField.Body, "[{\"a\":1}]").toJson()
                )
                .asJsonObject
        assertEquals(1, array["body"].asJsonArray[0].asJsonObject["a"].asInt)
        val text =
            JsonParser.parseString(UrlOptionDraft().update(UrlOptionField.Body, "a=1&b=2").toJson())
                .asJsonObject
        assertEquals("a=1&b=2", text["body"].asString)
    }

    @Test
    fun retriesAreNumbersAndInvalidInputIsOmitted() {
        val json =
            JsonParser.parseString(UrlOptionDraft().update(UrlOptionField.Retry, "12").toJson())
                .asJsonObject
        assertEquals(12, json["retry"].asInt)
        assertEquals(
            "{}",
            UrlOptionDraft()
                .update(UrlOptionField.Retry, "invalid")
                .update(UrlOptionField.Headers, "invalid")
                .toJson(),
        )
    }

    @Test
    fun allOtherFieldsPreserveValuesAndDnsTrims() {
        val json =
            JsonParser.parseString(
                    UrlOptionDraft(webView = true)
                        .update(UrlOptionField.Method, "CUSTOM")
                        .update(UrlOptionField.Charset, "GBK")
                        .update(UrlOptionField.Type, "image")
                        .update(UrlOptionField.DnsIp, " 127.0.0.1 ")
                        .toJson()
                )
                .asJsonObject
        assertEquals("CUSTOM", json["method"].asString)
        assertEquals("GBK", json["charset"].asString)
        assertEquals("image", json["type"].asString)
        assertEquals("127.0.0.1", json["dnsIp"].asString)
        assertTrue(json["webView"].asBoolean)
    }

    @Test
    fun editingDraftDoesNotMutateEarlierSnapshot() {
        val old = UrlOptionDraft()
        val edited = old.update(UrlOptionField.Method, "POST")
        assertEquals("", old[UrlOptionField.Method])
        assertEquals("POST", edited[UrlOptionField.Method])
    }
}
