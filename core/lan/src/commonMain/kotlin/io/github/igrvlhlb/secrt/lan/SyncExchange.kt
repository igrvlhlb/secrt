package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.FolderPayload
import io.github.igrvlhlb.secrt.model.LabelPayload
import io.github.igrvlhlb.secrt.model.RecordType
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.model.VersionVector
import io.github.igrvlhlb.secrt.session.KeySlotsComparison
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.vault.InvalidVaultHeaderException

/** What one sync changed. */
data class SyncStats(
    /** Changes from the other device that were new here. */
    val received: Int,
    /** Changes sent to the other device. */
    val sent: Int,
)

/** One side's part in agreeing on key slots at the start of a sync ([SyncExchange], step 0). */
internal class KeySlotsTurn(
    val lan: LanSync,
    val peerName: String,
    /** Asks this side's user, through [LanSync.decide]. */
    val ask: suspend (LanQuestion) -> Boolean,
    /** The other side is asking its user: this one waits for the answer. */
    val onWaiting: () -> Unit = {},
)

/**
 * Two-way sync of one diary between two authenticated replicas, taking turns so neither side ever
 * waits on a full send buffer:
 *
 * 0. Both send their `vault.key` (the initiator first) and agree on the newest key slots: the most
 *    recent password or recovery-key change wins (see docs/security.md). The side whose key slots
 *    are older asks its user whether to take the other's on; if they are the same (or neither is
 *    stamped), nobody asks. A "no" ends the sync right here, before anything else is exchanged. A
 *    yes takes them on once the sync completed, so an interrupted sync leaves the password as it
 *    was (and asks again next time). Both copies travel inside the authenticated channel, which
 *    only paired devices of this diary can open, and are checked against this diary's data key.
 * 1. Both exchange version vectors (the initiator first).
 * 2. The initiator sends the revisions the responder lacks, in batches; the responder asks for the
 *    attachments it lacks among those they reference, receives them, then applies the revisions.
 * 3. The same in the other direction.
 * 4. `Done` both ways, so a cut connection is never taken for a finished sync.
 *
 * Attachments are stored before the revisions referencing them are applied, so an interrupted sync
 * can't leave entries pointing at attachments that a later sync would never send. Outgoing
 * revisions come from [DiaryStore.revisionsSince], which marks them shared.
 */
internal object SyncExchange {
    private const val BATCH_REVISIONS = 200
    private const val BATCH_PAYLOAD_CHARS = 4 * 1024 * 1024
    private const val MAX_REVISIONS = 1_000_000
    private const val MAX_VECTOR_ENTRIES = 10_000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_KEY_SLOTS_CHARS = 64 * 1024

    suspend fun run(channel: MessageChannel, store: DiaryStore, initiator: Boolean, keySlots: KeySlotsTurn): SyncStats {
        val lan = keySlots.lan
        val ownKeySlots = lan.ownKeySlots()
        val theirKeySlots = if (initiator) {
            channel.send(KeySlots(ownKeySlots))
            channel.expect<KeySlots>().vaultKey
        } else {
            channel.expect<KeySlots>().vaultKey.also { channel.send(KeySlots(ownKeySlots)) }
        }
        val comparison = try {
            if (theirKeySlots.length > MAX_KEY_SLOTS_CHARS) throw LanException.Protocol("key slots size")
            lan.compareKeySlots(theirKeySlots)
        } catch (e: InvalidVaultHeaderException) {
            channel.send(Failure(Failure.Reason.Protocol))
            throw LanException.Protocol("key slots of another diary", e)
        }
        val adopting = (comparison as? KeySlotsComparison.NewerThere)?.change
        // Asked before reading on: the other side waits (for this side's vector, or after sending
        // its own), so nothing it sent is left unread if this ends here.
        val answer = adopting?.let { lan.decide(LanQuestion.AdoptKeySlots(keySlots.peerName, it), keySlots.ask) }
        if (comparison == KeySlotsComparison.NewerHere) keySlots.onWaiting()

        val ownVector = store.versionVector()
        val theirVector = if (initiator) {
            if (answer != null && answer != LanSync.Answer.Yes) {
                lan.refuse(channel, answer, LanSync.PROTOCOL_KEY_SLOTS, Failure.Reason.KeySlotsDeclined, keySlots.peerName)
            }
            channel.send(Vector(ownVector))
            channel.expect<Vector>().versionVector
        } else {
            channel.expect<Vector>().versionVector.also {
                if (answer != null && answer != LanSync.Answer.Yes) {
                    lan.refuse(channel, answer, LanSync.PROTOCOL_KEY_SLOTS, Failure.Reason.KeySlotsDeclined, keySlots.peerName)
                }
                channel.send(Vector(ownVector))
            }
        }
        validate(theirVector)

        val stats = if (initiator) {
            val sent = send(channel, store, theirVector)
            SyncStats(received = receive(channel, store), sent = sent)
        } else {
            val received = receive(channel, store)
            SyncStats(received = received, sent = send(channel, store, theirVector))
        }

        if (initiator) {
            channel.send(Done)
            channel.expect<Done>()
        } else {
            channel.expect<Done>()
            channel.send(Done)
        }
        if (adopting != null) lan.adoptKeySlots(adopting)
        return stats
    }

    private suspend fun send(channel: MessageChannel, store: DiaryStore, theirVector: VersionVector): Int {
        val revisions = store.revisionsSince(theirVector)
        batches(revisions).forEach { channel.send(Revisions(it)) }
        channel.send(RevisionsEnd)

        val wanted = channel.expect<NeedAttachments>().blobIds
        val referenced = referencedAttachments(revisions)
        for (blobId in wanted) {
            // Only what these revisions reference: the other side can't fish for anything else.
            val encrypted = if (blobId in referenced) store.readEncryptedAttachment(blobId) else null
            if (encrypted != null) channel.sendAttachment(blobId, encrypted) else channel.send(AttachmentMissing(blobId))
        }
        channel.send(AttachmentsEnd)
        return revisions.size
    }

    private suspend fun receive(channel: MessageChannel, store: DiaryStore): Int {
        val revisions = mutableListOf<Revision>()
        while (true) {
            when (val message = channel.receiveMessage()) {
                is Revisions -> {
                    message.revisions.forEach { if (!it.isValid()) throw LanException.Protocol("invalid change") }
                    revisions += message.revisions
                    if (revisions.size > MAX_REVISIONS) throw LanException.Protocol("too many changes")
                }
                RevisionsEnd -> break
                else -> throw LanException.Protocol("unexpected message")
            }
        }

        val missing = referencedAttachments(revisions).filterNot { store.hasAttachment(it) }
        channel.send(NeedAttachments(missing))
        val pending = missing.toMutableSet()
        while (true) {
            when (val incoming = channel.receive()) {
                is Incoming.Attachment -> {
                    if (!pending.remove(incoming.blobId)) throw LanException.Protocol("unrequested attachment")
                    try {
                        store.putEncryptedAttachment(incoming.blobId, incoming.encrypted)
                    } catch (e: DecryptionException) {
                        throw LanException.Protocol("damaged attachment", e)
                    }
                }
                is Incoming.Message -> when (val message = incoming.message) {
                    // The sender lacks it too; the entry shows it as missing until some device has it.
                    is AttachmentMissing -> if (!pending.remove(message.blobId)) throw LanException.Protocol("unrequested attachment")
                    AttachmentsEnd -> break
                    is Failure -> throw message.toException()
                    else -> throw LanException.Protocol("unexpected message")
                }
                is Incoming.Chunk -> throw LanException.Protocol("unexpected data")
            }
        }
        if (pending.isNotEmpty()) throw LanException.Protocol("attachments missing")
        return store.applyRemote(revisions)
    }

    private fun batches(revisions: List<Revision>): List<List<Revision>> {
        val batches = mutableListOf<List<Revision>>()
        var current = mutableListOf<Revision>()
        var chars = 0
        for (revision in revisions) {
            val size = revision.payload?.length ?: 0
            if (current.isNotEmpty() && (current.size >= BATCH_REVISIONS || chars + size > BATCH_PAYLOAD_CHARS)) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += revision
            chars += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun referencedAttachments(revisions: List<Revision>): Set<String> =
        revisions.asSequence()
            .filter { it.recordType == RecordType.ENTRY && it.payload != null }
            .flatMap { SecrtJson.decodeFromString<EntryPayload>(it.payload!!).attachments }
            .map { it.blobId }
            .filter(::isValidBlobId)
            .toSet()

    private fun validate(vector: VersionVector) {
        if (vector.size > MAX_VECTOR_ENTRIES || vector.any { (device, seq) -> device.length > MAX_ID_LENGTH || seq < 0 }) {
            throw LanException.Protocol("invalid version vector")
        }
    }

    /**
     * Revisions are only accepted from an authenticated peer, but are still checked for shape, so a
     * buggy or newer peer can't make the merge engine fail halfway or store nonsense.
     */
    private fun Revision.isValid(): Boolean {
        if (seq <= 0 || id != Revision.idOf(deviceId, seq)) return false
        if (deviceId.isEmpty() || deviceId.length > MAX_ID_LENGTH || recordId.isEmpty() || recordId.length > MAX_ID_LENGTH) return false
        if (parents.size > 64 || parents.any { it.length > 2 * MAX_ID_LENGTH }) return false
        if (deleted != (payload == null)) return false
        val text = payload ?: return true
        return try {
            when (recordType) {
                RecordType.ENTRY -> SecrtJson.decodeFromString<EntryPayload>(text)
                RecordType.FOLDER -> SecrtJson.decodeFromString<FolderPayload>(text)
                RecordType.LABEL -> SecrtJson.decodeFromString<LabelPayload>(text)
            }
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
