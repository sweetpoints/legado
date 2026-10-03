package io.legado.app.ui.qrcode

import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.Result
import com.king.camera.scan.BaseCameraScan
import com.king.camera.scan.CameraScan
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.analyze.MultiFormatAnalyzer
import io.legado.app.R

/** CameraX preview is the sole rendered native view; controls and scan guide belong to Compose. */
@Composable
internal fun QrCameraPreview(enabled: Boolean, onResult: (String?) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    var scanner by remember(owner) { mutableStateOf<BaseCameraScan<Result>?>(null) }
    var flashlightVisible by remember(owner) { mutableStateOf(false) }
    var torch by remember(owner) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).also { preview ->
                    val camera = BaseCameraScan<Result>(context, owner, preview)
                    val config =
                        DecodeConfig()
                            .setHints(DecodeFormatManager.QR_CODE_HINTS)
                            .setFullAreaScan(true)
                            .setAreaRectRatio(.8f)
                    camera.setAnalyzer(MultiFormatAnalyzer(config))
                    camera.setOnScanResultCallback(
                        CameraScan.OnScanResultCallback { scanned ->
                            camera.setAnalyzeImage(false)
                            result(scanned.result?.text)
                        }
                    )
                    // Adapt the library's ambient-light signal without mounting its legacy
                    // flashlight layout.
                    val lightSignal =
                        object : View(context) {
                            override fun setVisibility(visibility: Int) {
                                super.setVisibility(visibility)
                                flashlightVisible = visibility == VISIBLE
                            }

                            override fun setSelected(selected: Boolean) {
                                super.setSelected(selected)
                                torch = selected
                            }
                        }
                    lightSignal.visibility = View.INVISIBLE
                    camera.bindFlashlightView(lightSignal)
                    camera.setAnalyzeImage(enabled)
                    scanner = camera
                    camera.startCamera()
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { scanner?.setAnalyzeImage(enabled) },
        )
        if (flashlightVisible || torch)
            TextButton(
                onClick = {
                    scanner?.let { camera ->
                        torch = !camera.isTorchEnabled
                        camera.enableTorch(torch)
                    }
                },
                modifier = Modifier.align(Alignment.Center).testTag("qr-flashlight"),
            ) {
                Text(
                    stringResource(
                        if (torch) R.string.qr_flashlight_off else R.string.qr_flashlight_on
                    )
                )
            }
    }
    DisposableEffect(scanner) {
        val camera = scanner
        onDispose { camera?.release() }
    }
}
