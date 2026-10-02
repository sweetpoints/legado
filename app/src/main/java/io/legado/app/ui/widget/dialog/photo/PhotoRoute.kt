package io.legado.app.ui.widget.dialog.photo

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive

/** Static copies use GC ownership; animations hold a Glide lease while composed. */
data class PhotoUiState(val bitmap: Bitmap? = null, val isLoading: Boolean = true,
    val error: String? = null, val animation: PhotoImage.Animated? = null)

@Composable
fun PhotoRoute(request: PhotoRequest, loader: PhotoImageLoader, onClose: () -> Unit,
    isEInk: Boolean = false, modifier: Modifier = Modifier) {
    var state by remember(request, loader) { mutableStateOf(PhotoUiState()) }
    LaunchedEffect(request, loader) {
        var animation: PhotoImage.Animated? = null
        try {
            val image = loader.load(request)
            animation = image as? PhotoImage.Animated
            coroutineContext.ensureActive()
            state = when (image) {
                is PhotoImage.Static -> PhotoUiState(image.bitmap, false)
                is PhotoImage.Animated -> PhotoUiState(isLoading = false, animation = image)
            }
            awaitCancellation()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            coroutineContext.ensureActive()
            state = PhotoUiState(isLoading = false, error = error.localizedMessage ?: error.toString())
        } finally { animation?.release() }
    }
    PhotoScreen(state, onClose, isEInk, modifier, imageKey = request.src)
}
