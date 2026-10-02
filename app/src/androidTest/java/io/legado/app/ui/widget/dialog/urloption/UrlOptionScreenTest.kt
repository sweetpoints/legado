package io.legado.app.ui.widget.dialog.urloption

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import com.google.gson.JsonParser
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UrlOptionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val charsets = listOf("UTF-8", "GBK")

    @Test fun confirmsScriptsAndDnsFromScrolledFieldsOnce() {
        val results = mutableListOf<String>(); var closes = 0
        compose.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, { results += it }, { closes++ }) } }
        compose.onNodeWithTag("url-option-js").performScrollTo().performTextReplacement("request()\nnext()")
        compose.onNodeWithTag("url-option-bodyJs").performScrollTo().performTextReplacement("response()")
        compose.onNodeWithTag("url-option-dnsIp").performScrollTo().performTextReplacement(" 127.0.0.1 ")
        compose.onNodeWithTag("url-option-confirm").performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            assertEquals(1, results.size)
            val json = JsonParser.parseString(results.single()).asJsonObject
            assertEquals("request()\nnext()", json["js"].asString)
            assertEquals("response()", json["bodyJs"].asString)
            assertEquals("127.0.0.1", json["dnsIp"].asString)
        }
    }
    @Test fun backdropCancelsWithoutDelivering() {
        var saves = 0; var closes = 0
        compose.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, { saves++ }, { closes++ }) } }
        compose.onNodeWithTag("url-option-backdrop").performTouchInput { click(Offset(8f, 8f)) }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun cardClickDoesNotCancel() {
        var closes = 0
        compose.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, {}, { closes++ }) } }
        compose.onNodeWithTag("url-option-card").performClick()
        compose.runOnIdle { assertEquals(0, closes) }
    }
    @Test fun draftsAndCheckboxSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        var result = ""
        restoration.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, { result = it }, {}) } }
        compose.onNodeWithTag("url-option-webview").performScrollTo().performClick()
        compose.onNodeWithTag("url-option-body").performScrollTo().performTextReplacement("hello\nworld")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("url-option-body").performScrollTo().assertTextContains("hello\nworld")
        compose.onNodeWithTag("url-option-confirm").performClick()
        compose.runOnIdle {
            val json = JsonParser.parseString(result).asJsonObject
            assertTrue(json["webView"].asBoolean); assertEquals("hello\nworld", json["body"].asString)
        }
    }
    @Test fun restoredFinishedClosesWithoutSecondSuccess() {
        val restoration = StateRestorationTester(compose)
        var saves = 0; var closes = 0
        restoration.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, { saves++ }, { closes++ }) } }
        compose.onNodeWithTag("url-option-confirm").performClick()
        compose.waitUntil { closes == 1 }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil { closes == 2 }
        compose.runOnIdle { assertEquals(1, saves) }
    }
    @Test fun suggestionMenusStillPermitCustomMethod() {
        var result = ""
        compose.setContent { LegadoComposeTheme { UrlOptionRoute(charsets, { result = it }, {}) } }
        compose.onNodeWithTag("url-option-method").performScrollTo().performClick()
        compose.onNodeWithTag("url-option-suggestion-POST").performClick()
        compose.onNodeWithTag("url-option-method").performTextReplacement("CUSTOM")
        compose.onNodeWithTag("url-option-charset").performScrollTo().performClick()
        compose.onNodeWithTag("url-option-suggestion-GBK").performClick()
        compose.onNodeWithTag("url-option-confirm").performClick()
        compose.runOnIdle {
            val json = JsonParser.parseString(result).asJsonObject
            assertEquals("CUSTOM", json["method"].asString); assertEquals("GBK", json["charset"].asString)
        }
    }
}
