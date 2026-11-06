package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.igrvlhlb.secrt.crypto.decodeBase64
import io.github.igrvlhlb.secrt.crypto.toBase64
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.model.VersionVector
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.igrvlhlb.secrt.storage.db.Peer as PeerRow

/** A device paired with this one for LAN sync. */
data class Peer(
    /** The peer's Ed25519 identity public key (base64url): what identifies it on every connection. */
    val identityKey: String,
    val name: String,
    /** The device id in the peer's revisions; `null` until known. */
    val deviceId: String?,
    val pairedAt: Long,
    val lastSyncAt: Long?,
    /** Where this device last reached the peer (`host:port`), most recent first. Empty for peers that connect to us. */
    val addresses: List<String>,
    /** The peer's version vector as of the last sync. */
    val versionVector: VersionVector?,
)

/**
 * This replica's paired devices and its own LAN identity, kept in the encrypted database: the
 * identity's private key never exists outside it (or memory). Peers are local to each replica and
 * never synced.
 */
class PeerStore internal constructor(
    private val store: DiaryStore,
    private val db: SecrtDatabase,
    private val dispatcher: CoroutineDispatcher,
) {
    fun observe(): Flow<List<Peer>> = with(store) {
        db.peerQueries.all().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toPeer() } }.ignoreAfterClose()
    }

    suspend fun all(): List<Peer> = store.read { db.peerQueries.all().executeAsList().map { it.toPeer() } }

    suspend fun get(identityKey: String): Peer? = store.read { db.peerQueries.byKey(identityKey).executeAsOneOrNull()?.toPeer() }

    /** Adds a newly paired device, or refreshes one paired before (keeping its sync history). */
    suspend fun pair(identityKey: String, name: String, deviceId: String?, addresses: List<String>, now: Long) = store.write {
        db.transaction {
            val joined = addresses.joinOrNull()
            db.peerQueries.insertIfAbsent(identityKey, name, deviceId, now, joined)
            db.peerQueries.refresh(name = name, device_id = deviceId, addresses = joined, identity_key = identityKey)
        }
    }

    /** Remembers a successful sync. Does nothing if the peer was forgotten meanwhile. */
    suspend fun recordSync(identityKey: String, name: String, deviceId: String?, addresses: List<String>, versionVector: VersionVector, now: Long) =
        store.write {
            db.peerQueries.recordSync(
                name = name,
                device_id = deviceId,
                last_sync_at = now,
                addresses = addresses.joinOrNull(),
                version_vector = SecrtJson.encodeToString(versionVector),
                identity_key = identityKey,
            )
        }

    /** Unpairs a device: it can no longer connect, and must be paired again to sync. */
    suspend fun forget(identityKey: String) = store.write { db.peerQueries.delete(identityKey) }

    /**
     * The 32-byte seed of this replica's Ed25519 identity, created with [generate] on first use.
     * The caller should wipe the returned copy when done with it.
     */
    suspend fun identitySeed(generate: () -> ByteArray): ByteArray = store.write {
        db.metaQueries.get(META_IDENTITY_SEED).executeAsOneOrNull()?.decodeBase64()
            ?: generate().also { db.metaQueries.put(META_IDENTITY_SEED, it.toBase64()) }
    }

    /**
     * Installs an identity created before this database existed: a computer set up from a phone
     * pairs (and so shows its identity) before it has a diary.
     */
    suspend fun setIdentitySeed(seed: ByteArray) = store.write { db.metaQueries.put(META_IDENTITY_SEED, seed.toBase64()) }

    private fun PeerRow.toPeer() = Peer(
        identityKey = identity_key,
        name = name,
        deviceId = device_id,
        pairedAt = paired_at,
        lastSyncAt = last_sync_at,
        addresses = addresses?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
        versionVector = version_vector?.let { runCatching { SecrtJson.decodeFromString<VersionVector>(it) }.getOrNull() },
    )

    private fun List<String>.joinOrNull(): String? = takeIf { it.isNotEmpty() }?.joinToString(",")

    private companion object {
        const val META_IDENTITY_SEED = "lan_identity_seed"
    }
}
