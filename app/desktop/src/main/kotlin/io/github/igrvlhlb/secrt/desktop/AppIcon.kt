package io.github.igrvlhlb.secrt.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private val Paper = Color(0xFFFBF8F3)
private val Ink = Color(0xFF4A5C92)

/** The Android launcher icon (a closed book with a keyhole), used as the window icon. */
val AppIcon: ImageVector = ImageVector.Builder("secrt", 108.dp, 108.dp, 108f, 108f)
    .addPath(addPathNodes("M24,0h60a24,24 0,0 1,24 24v60a24,24 0,0 1,-24 24H24a24,24 0,0 1,-24 -24V24a24,24 0,0 1,24 -24z"), fill = SolidColor(Ink))
    .addPath(addPathNodes("M38,32h30a4,4 0,0 1,4 4v36a4,4 0,0 1,-4 4H38a4,4 0,0 1,-4 -4V36a4,4 0,0 1,4 -4z"), fill = SolidColor(Paper))
    .addPath(addPathNodes("M38,32h4v44h-4a4,4 0,0 1,-4 -4V36a4,4 0,0 1,4 -4z"), fill = SolidColor(Ink))
    .addPath(addPathNodes("M57,46a5,5 0,1 1,-3 9.6l1.5,9.4h-5l1.5,-9.4A5,5 0,0 1,57 46z"), fill = SolidColor(Ink))
    .build()
