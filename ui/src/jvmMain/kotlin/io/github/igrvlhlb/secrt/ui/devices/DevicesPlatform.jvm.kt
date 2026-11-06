package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal actual val isComputer: Boolean = true

internal actual fun qrModules(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    // Size 0: one matrix cell per module; the UI scales it.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}

@Composable
internal actual fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier) = Unit
