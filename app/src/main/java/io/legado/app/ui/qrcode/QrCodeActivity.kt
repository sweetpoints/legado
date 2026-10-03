package io.legado.app.ui.qrcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.Result
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.FileQrScanRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

class QrCodeActivity : BaseComposeActivity(), ScanResultCallback {
    internal val model by
        viewModels<QrScanViewModel> {
            viewModelFactory {
                initializer {
                    QrScanViewModel(
                        FileQrScanRepository(applicationContext),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private var picking by mutableStateOf(false)
    private val gallery =
        registerForActivityResult(HandleFileContract()) { result ->
            picking = false
            model.gallery(result.uri?.toString())
        }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        picking = savedInstanceState?.getBoolean("qr.picking") == true
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val permission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed
                ->
                granted = allowed
                if (!allowed) finish()
            }
        LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
        QrScanRoute(
            model,
            granted,
            { !isFinishing && !isDestroyed },
            {
                picking = true
                try {
                    gallery.launch { mode = HandleFileContract.IMAGE }
                } catch (error: Exception) {
                    picking = false
                    toastOnUi(error.localizedMessage ?: "ERROR")
                }
            },
            { text ->
                setResult(RESULT_OK, Intent().putExtra("result", text))
                finish()
            },
            ::finish,
        ) { enabled ->
            if (granted) QrCameraPreview(enabled && !picking, model::capture)
        }
    }

    override fun onScanResultCallback(result: Result?) {
        model.capture(result?.text)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("qr.picking", picking)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            val captured = model
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.close() }
        }
        super.onDestroy()
    }
}
