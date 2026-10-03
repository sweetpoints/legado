package io.legado.app.ui.widget.toast

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.utils.runToastCallbackOnApi30
import io.legado.app.utils.toToastMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToastComposeContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun androidCharacterAndReplacementSpansBecomeComposeTextAndInlinePixels() {
        val source = SpannableString("Red *toast*")
        val replacementSpan = TestReplacementSpan()
        source.setSpan(
            ForegroundColorSpan(AndroidColor.RED),
            0,
            3,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        source.setSpan(StyleSpan(Typeface.BOLD), 4, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(UnderlineSpan(), 4, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(replacementSpan, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val message =
            source.toToastMessage(
                baseTextSizePx = 32f,
                density = Density(2f, 1f),
                color = AndroidColor.BLACK,
            )
        assertEquals(1, message.inlineImages.size)
        val styledText = message.annotatedText.spanStyles
        assertTrue(
            styledText.any { it.item.color == androidx.compose.ui.graphics.Color(AndroidColor.RED) }
        )
        assertTrue(
            styledText.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
        )
        assertTrue(styledText.any { it.item.textDecoration != null })
        val image = message.inlineImages.values.single()
        assertTrue(image.widthPx > 0)
        assertTrue(image.heightPx > 0)
        assertEquals(AndroidColor.GREEN, image.bitmap.getPixel(0, 0))
        assertEquals(32f, replacementSpan.drawTextSizePx, 0.01f)
        assertEquals(1f, replacementSpan.drawTextScaleX, 0.01f)

        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 100.dp).testTag("toast-test-host")) {
                ToastComposeContent(message, AndroidColor.DKGRAY, AndroidColor.WHITE)
            }
        }
        compose.onNodeWithTag("compose-toast-card").assertExists()
        compose.onNodeWithTag("compose-toast-message").assertTextContains("Red")
        val captured = compose.onNodeWithTag("compose-toast-message").captureToImage().toPixelMap()
        val cardPixels = compose.onNodeWithTag("compose-toast-card").captureToImage().toPixelMap()
        assertTrue(
            "The Toast must use the selected theme card background",
            (0 until cardPixels.width).any { x ->
                (0 until cardPixels.height).any { y ->
                    val pixel = cardPixels[x, y]
                    pixel.red in 0.20f..0.35f &&
                        pixel.green in 0.20f..0.35f &&
                        pixel.blue in 0.20f..0.35f
                }
            },
        )
        assertTrue(
            "The actual Compose text slot must contain rendered pixels",
            (0 until captured.width).any { x ->
                (0 until captured.height).any { y -> captured[x, y].alpha > 0.1f }
            },
        )
        assertTrue(
            "The ReplacementSpan's Compose inline image must retain its drawn green pixels",
            (0 until captured.width).any { x ->
                (0 until captured.height).any { y ->
                    val pixel = captured[x, y]
                    pixel.green > 0.6f && pixel.red < 0.4f && pixel.blue < 0.4f
                }
            },
        )
        assertTrue(
            "The Toast must use its selected text color",
            (0 until captured.width).any { x ->
                (0 until captured.height).any { y ->
                    val pixel = captured[x, y]
                    pixel.red > 0.9f && pixel.green > 0.9f && pixel.blue > 0.9f
                }
            },
        )
    }

    @Test
    fun metricSpansAreAppliedOnceWhenConvertedForComposeAndReplacementPixels() {
        val density = Density(2f, 1f)
        val halfSize = SpannableString("half")
        halfSize.setSpan(
            RelativeSizeSpan(0.5f),
            0,
            halfSize.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val halfSizeStyle =
            halfSize
                .toToastMessage(32f, density, AndroidColor.BLACK)
                .annotatedText
                .spanStyles
                .single()
                .item
        assertEquals(16f, with(density) { halfSizeStyle.fontSize.toPx() }, 0.01f)

        val doubleSize = SpannableString("double")
        doubleSize.setSpan(
            RelativeSizeSpan(2f),
            0,
            doubleSize.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val doubleSizeStyle =
            doubleSize
                .toToastMessage(32f, density, AndroidColor.BLACK)
                .annotatedText
                .spanStyles
                .single()
                .item
        assertEquals(64f, with(density) { doubleSizeStyle.fontSize.toPx() }, 0.01f)

        val stretched = SpannableString("wide")
        stretched.setSpan(ScaleXSpan(2f), 0, stretched.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val transform =
            stretched
                .toToastMessage(32f, density, AndroidColor.BLACK)
                .annotatedText
                .spanStyles
                .single()
                .item
                .textGeometricTransform
        assertEquals(2f, requireNotNull(transform).scaleX, 0.01f)

        val replacementSource = SpannableString("x")
        val replacementSpan = TestReplacementSpan()
        replacementSource.setSpan(RelativeSizeSpan(0.5f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        replacementSource.setSpan(ScaleXSpan(2f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        replacementSource.setSpan(replacementSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        replacementSource.toToastMessage(32f, density, AndroidColor.BLACK)
        assertEquals(16f, replacementSpan.drawTextSizePx, 0.01f)
        assertEquals(2f, replacementSpan.drawTextScaleX, 0.01f)
    }

    @Test
    fun toastPresentationUsesAndReleasesItsOwnLifecycleOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var presentation: ToastComposePresentation
        lateinit var owner: Lifecycle
        var attachedEvents = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val message =
                "independent owner".toToastMessage(32f, Density(2f, 1f), AndroidColor.BLACK)
            presentation =
                ToastComposePresentation(
                    context,
                    message,
                    AndroidColor.DKGRAY,
                    AndroidColor.WHITE,
                ) {
                    attachedEvents++
                }
            val viewOwner = presentation.view.findViewTreeLifecycleOwner()
            assertNotNull(viewOwner)
            owner = requireNotNull(viewOwner).lifecycle
            assertEquals(Lifecycle.State.CREATED, owner.currentState)
            compose.activity.addContentView(
                presentation.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            assertEquals(Lifecycle.State.RESUMED, owner.currentState)
            assertEquals(1, attachedEvents)
            (compose.activity.window.decorView as android.view.ViewGroup).removeView(
                presentation.view
            )
            assertEquals(Lifecycle.State.DESTROYED, owner.currentState)
            presentation.close()
            presentation.close()
        }
    }

    @Test
    fun preApi30ToastCallbackPathNeverInvokesCallbackFactory() {
        var callbackFactories = 0
        (26..29).forEach { sdkInt ->
            val registration =
                runToastCallbackOnApi30(sdkInt) {
                    callbackFactories++
                    AutoCloseable {}
                }
            assertNull("API $sdkInt must not create Toast.Callback", registration)
        }
        assertEquals(0, callbackFactories)

        var leaseClosed = false
        val registration =
            runToastCallbackOnApi30(30) {
                callbackFactories++
                AutoCloseable { leaseClosed = true }
            }
        assertNotNull(registration)
        assertEquals(1, callbackFactories)
        requireNotNull(registration).close()
        assertTrue(leaseClosed)
    }

    private class TestReplacementSpan : ReplacementSpan() {
        var drawTextSizePx = 0f
        var drawTextScaleX = 0f

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            fm?.let { it.set(paint.fontMetricsInt) }
            return 18
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            drawTextSizePx = paint.textSize
            drawTextScaleX = paint.textScaleX
            paint.color = AndroidColor.GREEN
            canvas.drawRect(x, top.toFloat(), x + 18, bottom.toFloat(), paint)
        }
    }
}
