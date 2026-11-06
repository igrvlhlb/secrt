package io.github.igrvlhlb.secrt.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Centers content no wider than a comfortable reading width. The layout is phone-first: on a
 * desktop window or a tablet, full-width cards and text lines would be hard to read.
 */
fun Modifier.readableWidth(): Modifier = wrapContentWidth().widthIn(max = 720.dp)
