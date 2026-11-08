package io.github.igrvlhlb.secrt.backup

import io.github.igrvlhlb.secrt.archive.CorruptArchiveException
import io.github.igrvlhlb.secrt.archive.SecrtArchive
import io.github.igrvlhlb.secrt.archive.UnsupportedArchiveException
import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.model.VersionVector
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.KeySlotsChange
import io.github.igrvlhlb.secrt.session.KeySlotsComparison
import io.github.igrvlhlb.secrt.session.OpenDiary
import io.github.igrvlhlb.secrt.storage.BlobStore
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidVaultHeaderException
import io.github.igrvlhlb.secrt.vault.VaultKeys
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** Where a long backup operation is, for a progress indicator. */
data class BackupProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { Checking, Downloading, DownloadingAttachments, UploadingAttachments, Uploading }
}

/**
 * @property downloaded new changes merged into this device.
 * @property uploaded changes this device sent to the backup.
 * @property missingAttachments attachments the backup should hold but doesn't (written by a
 *   device that didn't have them); their entries are restored without them.
 */
data class BackupResult(val downloaded: Int = 0, val uploaded: Int = 0, val missingAttachments: Int = 0)

/**
 * Optional, user-triggered backup of the diary to a [BackupTarget] the user controls. The backup
 * is just another replica: devices push the revisions it lacks and pull the ones they lack, and the
 * merge engine does the rest, so any number of devices can share one backup.
 *
 * Layout under the target's root (see docs/architecture.md):
 * ```
 * secrt/<vaultId>/manifest.json          {"format":1,"vaultId":"…"}; plain
 * secrt/<vaultId>/vault.key              key slots, as on the devices; plain, no secrets in clear
 * secrt/<vaultId>/vault.key.sealed       the same, sealed with the archive key (AAD = vault id +
 *                                        file name): the copy other devices trust and take on
 * secrt/<vaultId>/log/<deviceId>-<n>.seg revisions, sealed like .secrt segments (archive key,
 *                                        AAD = vault id + "log/<deviceId>-<n>.seg")
 * secrt/<vaultId>/blobs/<blobId>         attachments, encrypted exactly as stored on the devices
 * ```
 * Each device only ever creates `log/<its own id>-<n>.seg` files, numbered after the highest one
 * already there, and never rewrites them, so pushes need no locking. A segment may carry other
 * devices' revisions (learned by LAN sync, say): the backup must hold everything this device has.
 *
 * What this device knows about each target (what it holds, which segments were read) lives in the
 * encrypted database next to the target's credentials, never on the target.
 *
 * The most recent password or recovery-key change wins on every device (see docs/security.md):
 * a push uploads this device's `vault.key` when its key slots are newer than the sealed copy's,
 * and a pull or sync that finds newer ones there asks first (`confirmKeySlots`), then takes them
 * on. The plain copy is never taken on: the server could put an old one back, or forge one.
 */
class CloudBackup(
    private val session: AppSession,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // ---------------------------------------------------------------- settings (diary unlocked)

    /** The target backups go to, or `null` if the user hasn't set one up. */
    suspend fun savedTarget(): BackupTargetConfig? = session.withOpenDiary { it.store.loadConfig() }

    /** Sets (or with `null` forgets, credentials included) the target backups go to. */
    suspend fun saveTarget(config: BackupTargetConfig?) = session.withOpenDiary { diary ->
        diary.store.putLocalSetting(KEY_TARGET, config?.let { SecrtJson.encodeToString(BackupTargetConfig.serializer(), it) })
    }

    /** When this device last finished uploading to [target] (epoch millis), if ever. */
    suspend fun lastBackupAt(target: BackupTarget): Long? = session.withOpenDiary { it.store.loadState(target).lastBackupAt }

    // ---------------------------------------------------------------- operations (diary unlocked)

    /**
     * Uploads what [target] doesn't have yet: `vault.key` (when absent, or when the password or
     * recovery key changed on this device), the missing attachments, then the revisions as new
     * segments. Doesn't download anything; see [sync].
     */
    suspend fun push(target: BackupTarget, onProgress: (BackupProgress) -> Unit = {}): BackupResult =
        session.withOpenDiary { BackupRun(it, target, onProgress).push() }

    /**
     * Downloads and merges the segments and attachments this device hasn't seen.
     *
     * If the backup holds newer key slots (another device changed the password or recovery key),
     * [confirmKeySlots] is asked first, before anything is downloaded: on yes they are taken on
     * once the pull succeeds, and this device opens with them from its next unlock; on no nothing
     * happens at all.
     *
     * @throws KeySlotsChangeDeclinedException if [confirmKeySlots] said no.
     */
    suspend fun pull(
        target: BackupTarget,
        onProgress: (BackupProgress) -> Unit = {},
        confirmKeySlots: suspend (KeySlotsChange) -> Boolean = { false },
    ): BackupResult {
        val accepted = askAboutKeySlots(target, onProgress, confirmKeySlots)
        return session.withOpenDiary { BackupRun(it, target, onProgress).pull(accepted) }
    }

    /**
     * [pull] then [push]: afterwards this device and the backup hold the same revisions (and key
     * slots). Asks [confirmKeySlots] as [pull] does; a no means neither happens.
     *
     * @throws KeySlotsChangeDeclinedException
     */
    suspend fun sync(
        target: BackupTarget,
        onProgress: (BackupProgress) -> Unit = {},
        confirmKeySlots: suspend (KeySlotsChange) -> Boolean = { false },
    ): BackupResult {
        val accepted = askAboutKeySlots(target, onProgress, confirmKeySlots)
        return session.withOpenDiary { diary ->
            val run = BackupRun(diary, target, onProgress)
            val pulled = run.pull(accepted)
            run.push().copy(downloaded = pulled.downloaded, missingAttachments = pulled.missingAttachments)
        }
    }

    /**
     * Asks [confirm] about the backup's key slots if they are newer than this device's. Outside the
     * session lock: the user may take a while, and the diary must be able to lock meanwhile (which
     * ends the operation).
     *
     * @return the change the user accepted, or `null` if there was nothing to ask.
     */
    private suspend fun askAboutKeySlots(
        target: BackupTarget,
        onProgress: (BackupProgress) -> Unit,
        confirm: suspend (KeySlotsChange) -> Boolean,
    ): KeySlotsChange? {
        val change = session.withOpenDiary { BackupRun(it, target, onProgress).newerKeySlots() } ?: return null
        if (!confirm(change)) throw KeySlotsChangeDeclinedException()
        return change
    }

    // ---------------------------------------------------------------- first run

    /** Ids of the diaries backed up on [target]. */
    suspend fun findDiaries(target: BackupTarget): List<String> =
        target.list(ROOT)
            .filter { it.endsWith("/") && ID.matches(it.dropLast(1)) }
            .map { it.dropLast(1) }
            .filter { VAULT_KEY in target.list(vaultDir(it)) }
            .sorted()

    /**
     * Recreates the diary [vaultId] from [target] on a device without one, like
     * [AppSession.restore] does from a file: it opens with the backup's password or recovery key,
     * and nothing is left behind if anything fails. [config] is remembered, so the restored diary
     * is already connected to its backup.
     *
     * @throws io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
     * @throws NoBackupFoundException
     * @throws io.github.igrvlhlb.secrt.archive.ArchiveException if the backup is damaged or too new.
     */
    suspend fun restore(
        target: BackupTarget,
        vaultId: String,
        credential: Credential,
        config: BackupTargetConfig? = null,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupResult {
        require(ID.matches(vaultId)) { "Invalid diary id" }
        val dir = vaultDir(vaultId)
        var fetched: Fetched? = null
        session.restoreFrom(
            credential,
            load = { restore ->
                onProgress(BackupProgress(BackupProgress.Stage.Checking, 0, 0))
                checkManifest(target, dir, vaultId, createIfMissing = false)
                val header = target.get(dir + VAULT_KEY)?.decodeToString() ?: throw NoBackupFoundException()
                val keys = restore.unlock(header)
                if (keys.vaultId != vaultId) throw CorruptArchiveException("its vault.key belongs to another diary")
                fetch(target, dir, restore.crypto, keys, restore.blobs, BackupState(), onProgress).also {
                    // The restored device installs this very vault.key: nothing to upload until it changes.
                    fetched = it.copy(state = it.state.copy(uploadedVaultKey = header))
                }.revisions
            },
            finish = { diary ->
                config?.let { diary.store.putLocalSetting(KEY_TARGET, SecrtJson.encodeToString(BackupTargetConfig.serializer(), it)) }
                diary.store.saveState(target, requireNotNull(fetched).state)
            },
        )
        val result = requireNotNull(fetched)
        return BackupResult(downloaded = result.revisions.size, missingAttachments = result.missingAttachments)
    }

    // ---------------------------------------------------------------- internals

    /** One push and/or pull, run while holding the open diary. */
    private inner class BackupRun(
        private val diary: OpenDiary,
        private val target: BackupTarget,
        private val onProgress: (BackupProgress) -> Unit,
    ) {
        private val store = diary.store
        private val dir = vaultDir(diary.keys.vaultId)
        private val ownId = store.deviceId

        /** The backup's key slots, if they are newer than this device's. */
        suspend fun newerKeySlots(): KeySlotsChange? {
            onProgress(BackupProgress(BackupProgress.Stage.Checking, 0, 0))
            if (VAULT_KEY !in target.list(dir)) return null
            checkManifest(target, dir, diary.keys.vaultId, createIfMissing = false)
            return (sealedKeySlots() as? KeySlotsComparison.NewerThere)?.change
        }

        /** @param accepted the newer key slots the user agreed to take on, if any (see [askAboutKeySlots]). */
        suspend fun pull(accepted: KeySlotsChange?): BackupResult {
            onProgress(BackupProgress(BackupProgress.Stage.Checking, 0, 0))
            // Nothing pushed there yet: nothing to pull either.
            if (VAULT_KEY !in target.list(dir)) return BackupResult()
            checkManifest(target, dir, diary.keys.vaultId, createIfMissing = false)
            // Asked about before the session lock was taken: another device may have pushed since.
            val newer = (sealedKeySlots() as? KeySlotsComparison.NewerThere)?.change
            if (newer != null && newer != accepted) throw BackupException("The backup changed meanwhile. Try again.")
            val fetched = fetch(target, dir, diary.crypto, diary.keys, diary.blobs, store.loadState(target), onProgress)
            val added = store.applyRemote(fetched.revisions)
            var state = fetched.state
            // Last, once everything else came through: a pull that fails leaves the password as it was.
            if (newer != null && diary.adoptKeySlots(newer)) state = state.copy(uploadedVaultKey = diary.vaultKeyJson)
            store.saveState(target, state)
            return BackupResult(downloaded = added, missingAttachments = fetched.missingAttachments)
        }

        /**
         * How the backup's sealed copy of `vault.key` compares with this device's; `null` if it has
         * none (pushed by versions from before it).
         *
         * @throws CorruptArchiveException if it was tampered with, or isn't this diary's.
         */
        private suspend fun sealedKeySlots(): KeySlotsComparison? {
            val data = target.get(dir + VAULT_KEY_SEALED) ?: return null
            val json = SecrtArchive.openFile(diary.crypto, diary.keys, VAULT_KEY_SEALED, data).decodeToString()
            return try {
                diary.compareKeySlots(json)
            } catch (e: InvalidVaultHeaderException) {
                throw CorruptArchiveException("$VAULT_KEY_SEALED doesn't belong to this diary", e)
            }
        }

        /** Whether the backup should get this device's `vault.key`, given what it holds. */
        private suspend fun vaultKeyOutdated(top: List<String>, state: BackupState): Boolean {
            if (VAULT_KEY !in top) return true
            sealedKeySlots()?.let { return it == KeySlotsComparison.NewerHere }
            // Pushed by versions from before the sealed copy. This device's key slots are compared
            // with the plain one (only to decide whether to replace it, never to take it on), or,
            // if neither is stamped, the old rule applies: only if they changed *here*.
            val plain = target.get(dir + VAULT_KEY)?.decodeToString()
            val newerHere = plain != null && try {
                diary.compareKeySlots(plain) == KeySlotsComparison.NewerHere
            } catch (e: InvalidVaultHeaderException) {
                true
            }
            return newerHere || state.uploadedVaultKey != null && state.uploadedVaultKey != diary.vaultKeyJson
        }

        suspend fun push(): BackupResult {
            onProgress(BackupProgress(BackupProgress.Stage.Checking, 0, 0))
            var state = store.loadState(target)
            val top = target.list(dir)
            checkManifest(target, dir, diary.keys.vaultId, createIfMissing = true)
            val ownSegments = segments(target.list(dir + LOG)).filter { it.device == ownId }
            if (VAULT_KEY !in top || (state.segments[ownId] ?: 0) > 0 && ownSegments.isEmpty()) {
                // The backup was deleted (or emptied) since: start over, uploading everything.
                state = BackupState()
            }

            // vault.key: only when it is missing or this device's key slots are newer than the
            // backup's, so a device that merely never saw another device's password change can't
            // put the old one back. In clear, for restoring on a new device, then sealed, the copy
            // other devices trust. In that order: if the push stops in between, the sealed copy
            // is still the older one, and the next push uploads both again.
            val vaultKey = diary.vaultKeyJson
            when {
                vaultKeyOutdated(top, state) -> {
                    target.put(dir + VAULT_KEY, vaultKey.encodeToByteArray())
                    target.put(dir + VAULT_KEY_SEALED, SecrtArchive.sealFile(diary.crypto, diary.keys, VAULT_KEY_SEALED, vaultKey.encodeToByteArray()))
                    state = state.copy(uploadedVaultKey = vaultKey)
                }
                state.uploadedVaultKey == null -> state = state.copy(uploadedVaultKey = vaultKey)
            }
            store.saveState(target, state)

            // Everything the backup lacks, as far as this device knows. Read through
            // revisionsSince, so this device's revisions in it are never amended in place again.
            val revisions = store.revisionsSince(state.remote)
            if (revisions.isNotEmpty()) {
                // Attachments first: a device that sees a segment must find its attachments.
                val present = target.list(dir + BLOBS).toSet()
                val missing = SecrtArchive.referencedBlobs(revisions).filter { it !in present && diary.blobs.exists(it) }
                missing.forEachIndexed { i, blobId ->
                    onProgress(BackupProgress(BackupProgress.Stage.UploadingAttachments, i, missing.size))
                    target.put(dir + BLOBS + blobId, diary.blobs.readEncrypted(blobId))
                }

                var number = maxOf(state.segments[ownId] ?: 0, ownSegments.maxOfOrNull { it.number } ?: 0)
                val chunks = revisions.chunked(MAX_REVISIONS_PER_SEGMENT)
                chunks.forEachIndexed { i, chunk ->
                    onProgress(BackupProgress(BackupProgress.Stage.Uploading, i, chunks.size))
                    number++
                    val name = "$LOG$ownId-$number$SEGMENT_SUFFIX"
                    target.put(dir + name, SecrtArchive.sealSegment(diary.crypto, diary.keys, name, chunk))
                    // Saved after each segment: an interrupted push resumes where it stopped.
                    state = state.copy(remote = state.remote.mergedWith(chunk), segments = state.segments + (ownId to number))
                    store.saveState(target, state)
                }
            }
            store.saveState(target, state.copy(lastBackupAt = clock()))
            return BackupResult(uploaded = revisions.size)
        }
    }

    /**
     * Downloads the segments [state] hasn't seen and the attachments they need, checking all of
     * them before anything is merged: a damaged or tampered file stops the whole pull.
     */
    private suspend fun fetch(
        target: BackupTarget,
        dir: String,
        crypto: Crypto,
        keys: VaultKeys,
        blobs: BlobStore,
        state: BackupState,
        onProgress: (BackupProgress) -> Unit,
    ): Fetched {
        val unseen = segments(target.list(dir + LOG))
            .filter { it.number > (state.segments[it.device] ?: 0) }
            .sortedWith(compareBy({ it.device }, { it.number }))
        val revisions = mutableListOf<Revision>()
        unseen.forEachIndexed { i, segment ->
            onProgress(BackupProgress(BackupProgress.Stage.Downloading, i, unseen.size))
            val data = target.get(dir + segment.name) ?: throw CorruptArchiveException("${segment.name} disappeared while reading it")
            revisions += SecrtArchive.openSegment(crypto, keys, segment.name, data).onEach {
                // Authentic but inconsistent data would still be a bug somewhere: don't merge it.
                if (it.id != Revision.idOf(it.deviceId, it.seq) || it.seq < 1) throw CorruptArchiveException("${segment.name} holds malformed revisions")
            }
        }

        val needed = SecrtArchive.referencedBlobs(revisions).filterNot(blobs::exists)
        var missing = 0
        needed.forEachIndexed { i, blobId ->
            onProgress(BackupProgress(BackupProgress.Stage.DownloadingAttachments, i, needed.size))
            val data = target.get(dir + BLOBS + blobId)
            if (data == null) {
                missing++
            } else {
                try {
                    blobs.putEncrypted(blobId, data)
                } catch (e: DecryptionException) {
                    throw CorruptArchiveException("the attachment $blobId can't be decrypted", e)
                }
            }
        }

        val read = unseen.groupBy { it.device }.mapValues { (_, s) -> s.maxOf { it.number } }
        val newState = state.copy(
            remote = state.remote.mergedWith(revisions),
            segments = state.segments + read.filter { (device, n) -> n > (state.segments[device] ?: 0) },
        )
        return Fetched(revisions, newState, missing)
    }

    private suspend fun checkManifest(target: BackupTarget, dir: String, vaultId: String, createIfMissing: Boolean) {
        val data = target.get(dir + MANIFEST)
        if (data == null) {
            if (createIfMissing) target.put(dir + MANIFEST, SecrtJson.encodeToString(Manifest(FORMAT, vaultId)).encodeToByteArray())
            return
        }
        val manifest = try {
            SecrtJson.decodeFromString<Manifest>(data.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CorruptArchiveException("$MANIFEST can't be read", e)
        }
        if (manifest.format > FORMAT) throw UnsupportedArchiveException(manifest.format)
        if (manifest.vaultId != vaultId) throw CorruptArchiveException("it belongs to another diary")
    }

    private data class Fetched(val revisions: List<Revision>, val state: BackupState, val missingAttachments: Int)

    private data class Segment(val name: String, val device: String, val number: Int)

    /** Segment files among a `log/` listing; anything else (newer formats, junk) is ignored. */
    private fun segments(names: List<String>): List<Segment> = names.mapNotNull { name ->
        val match = SEGMENT.matchEntire(name) ?: return@mapNotNull null
        val number = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        Segment(LOG + name, match.groupValues[1], number)
    }

    @Serializable
    private data class Manifest(val format: Int, val vaultId: String)

    private suspend fun DiaryStore.loadConfig(): BackupTargetConfig? =
        getLocalSetting(KEY_TARGET)?.let { json ->
            try {
                SecrtJson.decodeFromString(BackupTargetConfig.serializer(), json)
            } catch (e: IllegalArgumentException) {
                null // Written by a newer version with a target this one doesn't know.
            }
        }

    private suspend fun DiaryStore.loadState(target: BackupTarget): BackupState =
        getLocalSetting(stateKey(target))?.let { json ->
            try {
                SecrtJson.decodeFromString<BackupState>(json)
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: BackupState()

    private suspend fun DiaryStore.saveState(target: BackupTarget, state: BackupState) =
        putLocalSetting(stateKey(target), SecrtJson.encodeToString(state))

    private fun stateKey(target: BackupTarget) = "$KEY_STATE_PREFIX${target.id}"

    private companion object {
        const val FORMAT = 1
        const val ROOT = "secrt/"
        const val MANIFEST = "manifest.json"
        const val VAULT_KEY = "vault.key"
        const val VAULT_KEY_SEALED = "vault.key.sealed"
        const val LOG = "log/"
        const val BLOBS = "blobs/"
        const val SEGMENT_SUFFIX = ".seg"

        /** Keeps segments to a few MB at most, even on the first backup of a large diary. */
        const val MAX_REVISIONS_PER_SEGMENT = 2000

        const val KEY_TARGET = "backup.target"
        const val KEY_STATE_PREFIX = "backup.state:"

        /** Vault and device ids are UUIDs; anything else listed there isn't ours. */
        val ID = Regex("[0-9a-fA-F-]{8,64}")
        val SEGMENT = Regex("([0-9a-fA-F-]{8,64})-([0-9]{1,9})\\.seg")

        fun vaultDir(vaultId: String) = "$ROOT$vaultId/"
    }
}

/**
 * What this device knows about one target. A cache: losing it costs re-reading or re-uploading
 * (merging is idempotent), never data.
 */
@Serializable
internal data class BackupState(
    /** Revisions known to be on the target, read from it or written to it. Push sends the rest. */
    val remote: VersionVector = emptyMap(),
    /** Per writing device, the highest segment number already read (or written, for this device). */
    val segments: Map<String, Int> = emptyMap(),
    /** The `vault.key` this device last uploaded or found there in sync with its own. */
    val uploadedVaultKey: String? = null,
    val lastBackupAt: Long? = null,
)

private fun VersionVector.mergedWith(revisions: List<Revision>): VersionVector {
    val merged = toMutableMap()
    revisions.forEach { r -> if (r.seq > (merged[r.deviceId] ?: 0)) merged[r.deviceId] = r.seq }
    return merged
}
