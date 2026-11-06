package io.github.igrvlhlb.secrt.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.igrvlhlb.secrt.lan.PairingInvite
import io.github.igrvlhlb.secrt.ui.LocalAutoLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal actual val isComputer: Boolean = false

internal actual fun qrModules(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}

/**
 * CameraX preview plus an analyzer that decodes QR codes with ZXing: no Google Play services or
 * downloaded models, and the camera frames never leave the analyzer.
 */
@Composable
internal actual fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val autoLock = LocalAutoLock.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        autoLock.resume()
        granted = it
        asked = true
    }
    fun requestPermission() {
        // The system dialog must not count as leaving the app.
        autoLock.pause()
        permission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted) requestPermission() }

    if (granted) {
        CameraPreview(onScanned, modifier)
    } else if (asked) {
        Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("secrt needs the camera to scan the pairing code on your computer. It's only used while this screen is open.")
            Button(onClick = ::requestPermission) { Text("Allow camera") }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val reported = AtomicBoolean(false)
        val reader = QRCodeReader()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image ->
            val text = image.use { decode(reader, it) }
            if (text != null && text.startsWith(PairingInvite.PREFIX) && reported.compareAndSet(false, true)) {
                ContextCompat.getMainExecutor(context).execute { currentOnScanned(text) }
            }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get().also {
                runCatching { it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Decodes the luminance (Y) plane of a YUV frame; `null` when there's no readable QR code in it. */
private fun decode(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    // The last row may be shorter than the stride; the rest stays zero.
    val data = ByteArray(rowStride * image.height)
    buffer.get(data, 0, minOf(buffer.remaining(), data.size))
    val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
