package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Control messages exchanged once the channel is up. Attachments and archive chunks travel as binary frames. */
@Serializable
internal sealed interface LanMessage

/** First message each way: who is on the other end, and which diary it holds. */
@Serializable
@SerialName("hello")
internal data class Hello(
    val deviceName: String,
    /** The device id in this side's revisions; `null` without a diary. */
    val deviceId: String?,
    /** `null` when this side has no diary yet and wants to be set up from the other one. */
    val vaultId: String?,
    /**
     * What this side's app understands, so either side can treat an older one cleanly. Versions
     * before it didn't send it (they read as 1, and ignore it): 2 asks before sending a diary and
     * answers [Failure.Reason.SendDeclined] and [Failure.Reason.NoAnswer]; 3 starts every sync
     * with [KeySlots], and needs the other side to do the same.
     */
    val protocol: Int = 1,
) : LanMessage

@Serializable
@SerialName("failure")
internal data class Failure(val reason: Reason) : LanMessage {
    @Serializable
    enum class Reason {
        WrongCode, UnknownDevice, DifferentDiary, NoDiary, Locked, Busy, Protocol,

        /** The user turned down sending this diary. Only sent to peers of [Hello.protocol] 2 and later. */
        SendDeclined,

        /** Nobody answered the question in time. Only sent to peers of [Hello.protocol] 2 and later. */
        NoAnswer,

        /** The user turned down the sync, which would have changed this side's password. Protocol 3. */
        KeySlotsDeclined,
    }
}

/**
 * First message each way of a sync, right after the hellos: this side's `vault.key` (key slots
 * only, no secrets in clear), so both agree on the newest (see [SyncExchange]).
 */
@Serializable
@SerialName("keySlots")
internal data class KeySlots(val vaultKey: String) : LanMessage

@Serializable
@SerialName("vector")
internal data class Vector(val versionVector: Map<String, Long>) : LanMessage

@Serializable
@SerialName("revisions")
internal data class Revisions(val revisions: List<Revision>) : LanMessage

@Serializable
@SerialName("revisionsEnd")
internal data object RevisionsEnd : LanMessage

/** The attachments the receiver lacks among those the revisions just sent reference. */
@Serializable
@SerialName("needAttachments")
internal data class NeedAttachments(val blobIds: List<String>) : LanMessage

@Serializable
@SerialName("attachmentMissing")
internal data class AttachmentMissing(val blobId: String) : LanMessage

@Serializable
@SerialName("attachmentsEnd")
internal data object AttachmentsEnd : LanMessage

/** A whole-diary archive follows in chunks: how a device without a diary is set up from another one. */
@Serializable
@SerialName("archive")
internal data class ArchiveStart(val size: Long) : LanMessage

@Serializable
@SerialName("archiveEnd")
internal data object ArchiveEnd : LanMessage

@Serializable
@SerialName("received")
internal data object Received : LanMessage

@Serializable
@SerialName("done")
internal data object Done : LanMessage

/** What can arrive in a frame. */
internal sealed interface Incoming {
    data class Message(val message: LanMessage) : Incoming

    class Attachment(val blobId: String, val encrypted: ByteArray) : Incoming

    class Chunk(val bytes: ByteArray) : Incoming
}

/**
 * Frames over a [SecureChannel]: a type byte, then JSON for control messages, `id length | id |
 * bytes` for attachments, or raw bytes for archive chunks. A [Failure] from the other side is turned
 * into the matching [LanException] wherever a message is expected.
 */
internal class MessageChannel(val channel: SecureChannel) {

    /** How failures from the other side name it; set once its hello arrived. */
    var peerName: String = "The other device"

    suspend fun send(message: LanMessage) =
        channel.send(byteArrayOf(TYPE_MESSAGE) + json.encodeToString(LanMessage.serializer(), message).encodeToByteArray())

    suspend fun sendAttachment(blobId: String, encrypted: ByteArray) {
        val id = blobId.encodeToByteArray()
        channel.send(byteArrayOf(TYPE_ATTACHMENT, id.size.toByte()) + id + encrypted)
    }

    suspend fun sendChunk(bytes: ByteArray) = channel.send(byteArrayOf(TYPE_CHUNK) + bytes)

    /** @throws LanException.Protocol for anything malformed. */
    suspend fun receive(): Incoming {
        val frame = channel.receive()
        if (frame.isEmpty()) throw LanException.Protocol("empty frame")
        return when (frame[0]) {
            TYPE_MESSAGE -> {
                val message = try {
                    json.decodeFromString(LanMessage.serializer(), frame.decodeToString(1, frame.size))
                } catch (e: IllegalArgumentException) { // includes SerializationException
                    throw LanException.Protocol("unreadable message", e)
                }
                Incoming.Message(message)
            }
            TYPE_ATTACHMENT -> {
                if (frame.size < 2) throw LanException.Protocol("attachment frame")
                val idLength = frame[1].toInt() and 0xff
                if (frame.size < 2 + idLength) throw LanException.Protocol("attachment frame")
                val blobId = frame.decodeToString(2, 2 + idLength)
                if (!isValidBlobId(blobId)) throw LanException.Protocol("attachment id")
                Incoming.Attachment(blobId, frame.copyOfRange(2 + idLength, frame.size))
            }
            TYPE_CHUNK -> Incoming.Chunk(frame.copyOfRange(1, frame.size))
            else -> throw LanException.Protocol("frame type")
        }
    }

    /** Receives a control message, failing on anything else. */
    suspend fun receiveMessage(): LanMessage {
        val incoming = receive()
        val message = (incoming as? Incoming.Message)?.message ?: throw LanException.Protocol("unexpected data")
        if (message is Failure) throw message.toException(peerName)
        return message
    }

    suspend inline fun <reified T : LanMessage> expect(): T =
        receiveMessage() as? T ?: throw LanException.Protocol("unexpected message")

    companion object {
        private const val TYPE_MESSAGE: Byte = 1
        private const val TYPE_ATTACHMENT: Byte = 2
        private const val TYPE_CHUNK: Byte = 3

        private val json = Json(SecrtJson) { classDiscriminator = "type" }
    }
}

/** Blob ids are 32-byte keyed hashes in hex; anything else could be a path, so it never reaches storage. */
internal fun isValidBlobId(id: String): Boolean = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }

internal fun Failure.toException(peerName: String = "The other device"): LanException = when (reason) {
    Failure.Reason.WrongCode -> LanException.WrongCode()
    Failure.Reason.UnknownDevice -> LanException.UnknownDevice(peerName)
    Failure.Reason.DifferentDiary -> LanException.DifferentDiary()
    Failure.Reason.NoDiary -> LanException.NoDiary()
    Failure.Reason.Locked -> LanException.Locked()
    Failure.Reason.Busy -> LanException.Busy()
    Failure.Reason.Protocol -> LanException.Protocol("rejected by the other device")
    Failure.Reason.SendDeclined -> LanException.DeclinedThere("$peerName declined to send its diary.")
    Failure.Reason.NoAnswer -> LanException.DeclinedThere("Nobody answered on $peerName in time. Try again.")
    Failure.Reason.KeySlotsDeclined -> LanException.DeclinedThere("$peerName declined: its password would have changed.")
}
