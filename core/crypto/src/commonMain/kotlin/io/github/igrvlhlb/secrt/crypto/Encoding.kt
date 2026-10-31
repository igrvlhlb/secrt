package io.github.igrvlhlb.secrt.crypto

import kotlin.io.encoding.Base64

fun ByteArray.toBase64(): String = Base64.encode(this)

fun String.decodeBase64(): ByteArray = Base64.decode(this)

fun ByteArray.toHex(): String = toHexString()
