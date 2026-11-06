package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Computers listen and show pairing codes; phones scan them and connect. A phone never listens,
 * and a computer never gets a list of nearby phones.
 */
internal expect val isComputer: Boolean

/** The modules of a QR code for [text], row by row (`true` = dark), without the quiet zone. */
internal expect fun qrModules(text: String): Array<BooleanArray>

/**
 * A live camera view that reports the first secrt pairing code it sees. Asks for the camera
 * permission when first shown. Phones only.
 */
@Composable
internal expect fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier)

/** Opens the Devices screen; provided where navigation exists (the unlocked diary). */
val LocalOpenDevices = compositionLocalOf<(() -> Unit)?> { null }
