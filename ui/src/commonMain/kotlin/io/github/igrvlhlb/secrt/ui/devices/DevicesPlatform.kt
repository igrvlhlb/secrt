package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether this device can scan pairing codes (it has a camera: phones). Every device shows codes
 * and listens while its Devices screen is open; the one that scans connects, and later starts the
 * syncs. So computers, which can't scan, never connect out, and two computers can't pair yet.
 */
internal expect val canScanCodes: Boolean

/** The modules of a QR code for [text], row by row (`true` = dark), without the quiet zone. */
internal expect fun qrModules(text: String): Array<BooleanArray>

/**
 * A live camera view that reports the first secrt pairing code it sees. Asks for the camera
 * permission when first shown. Only where [canScanCodes].
 */
@Composable
internal expect fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier)

/** Opens the Devices screen; provided where navigation exists (the unlocked diary). */
val LocalOpenDevices = compositionLocalOf<(() -> Unit)?> { null }
