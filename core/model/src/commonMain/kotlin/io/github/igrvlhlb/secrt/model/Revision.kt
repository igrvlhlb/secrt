package io.github.igrvlhlb.secrt.model

import kotlinx.serialization.Serializable

enum class RecordType { ENTRY, FOLDER, LABEL }

/**
 * One immutable version of a record. Revisions are the source of truth: what the app shows is
 * derived from them, and syncing means exchanging the revisions the other side lacks.
 *
 * @property id unique id, `"<deviceId>:<seq>"`.
 * @property parents revisions this one was based on; more than one when it resolves a conflict.
 * @property seq per-device, gap-free counter, used for version vectors.
 * @property payload JSON of the record's payload type, `null` for deletions (tombstones).
 */
@Serializable
data class Revision(
    val id: String,
    val recordType: RecordType,
    val recordId: String,
    val parents: List<String>,
    val deviceId: String,
    val seq: Long,
    val hlc: Hlc,
    val deleted: Boolean,
    val payload: String?,
) {
    companion object {
        fun idOf(deviceId: String, seq: Long): String = "$deviceId:$seq"
    }
}

/** For each device, the highest revision `seq` known locally. */
typealias VersionVector = Map<String, Long>
