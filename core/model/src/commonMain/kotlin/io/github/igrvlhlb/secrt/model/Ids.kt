package io.github.igrvlhlb.secrt.model

import kotlin.uuid.Uuid

/** Generates a new random record or device identifier. */
fun newId(): String = Uuid.random().toString()
