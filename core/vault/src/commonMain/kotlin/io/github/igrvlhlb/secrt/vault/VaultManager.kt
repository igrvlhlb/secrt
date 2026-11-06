package io.github.igrvlhlb.secrt.vault

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.crypto.decodeBase64
import io.github.igrvlhlb.secrt.crypto.open
import io.github.igrvlhlb.secrt.crypto.seal
import io.github.igrvlhlb.secrt.crypto.toBase64
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.model.newId
import okio.FileSystem
import okio.Path

class InvalidCredentialsException : Exception("Wrong password or recovery key")

class InvalidVaultHeaderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Something that opens a vault. */
sealed interface Credential {
    data class Password(val password: String) : Credential

    data class RecoveryKey(val recoveryKey: String) : Credential
}

/**
 * Creates and unlocks the vault: a random data key, wrapped LUKS-style by a password slot and
 * an optional recovery-key slot, stored in `vault.key` inside [directory].
 *
 * Calls are blocking and CPU-heavy (Argon2id); run them off the main thread.
 */
class VaultManager(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val crypto: Crypto,
    private val argon2Params: Argon2Params = Argon2Params.Default,
) {
    private val headerPath = directory / "vault.key"

    fun exists(): Boolean = fileSystem.exists(headerPath)

    fun hasRecoveryKey(): Boolean = readHeader().slots.any { it is KeySlot.Recovery }

    /** @return the unlocked keys, and the recovery key to show the user once (if requested). */
    fun create(password: String, withRecoveryKey: Boolean): Pair<VaultKeys, String?> {
        check(!exists()) { "A vault already exists in $directory" }
        val vaultId = newId()
        val dataKey = crypto.randomBytes(Crypto.KEY_BYTES)
        val slots = mutableListOf<KeySlot>(passwordSlot(vaultId, dataKey, password))
        val recoveryKey = if (withRecoveryKey) {
            val (slot, key) = recoverySlot(vaultId, dataKey)
            slots += slot
            key
        } else {
            null
        }
        fileSystem.createDirectories(directory)
        writeHeader(VaultHeader(vaultId = vaultId, slots = slots, keyCheck = keyCheck(dataKey)))
        return VaultKeys(vaultId, crypto, dataKey) to recoveryKey
    }

    /** @throws InvalidCredentialsException */
    fun unlock(password: String): VaultKeys = unlock(readHeader(), Credential.Password(password))

    /** @throws InvalidCredentialsException */
    fun unlockWithRecoveryKey(recoveryKey: String): VaultKeys = unlock(readHeader(), Credential.RecoveryKey(recoveryKey))

    /**
     * Unlocks a `vault.key` that isn't installed here, e.g. one read from a backup, without
     * touching the local vault.
     *
     * @throws InvalidCredentialsException
     * @throws InvalidVaultHeaderException if [headerJson] isn't a vault header this app can read.
     */
    fun unlockExternal(headerJson: String, credential: Credential): VaultKeys = unlock(parseHeader(headerJson), credential)

    /** The installed `vault.key`, as stored. Contains no secrets in clear. */
    fun headerJson(): String = fileSystem.read(headerPath) { readUtf8() }

    /** Installs a `vault.key` (e.g. from a backup), replacing any existing one. */
    fun install(headerJson: String) {
        parseHeader(headerJson)
        fileSystem.createDirectories(directory)
        writeHeaderText(headerJson)
    }

    fun delete() {
        fileSystem.delete(headerPath, mustExist = false)
    }

    private fun unlock(header: VaultHeader, credential: Credential): VaultKeys {
        when (credential) {
            is Credential.Password -> for (slot in header.slots.filterIsInstance<KeySlot.Password>()) {
                val params = Argon2Params(slot.opsLimit, slot.memLimitBytes)
                val kek = crypto.argon2id(credential.password, slot.salt.decodeBase64(), params)
                unwrap(header.vaultId, slot, kek)?.let { return VaultKeys(header.vaultId, crypto, it) }
            }
            is Credential.RecoveryKey -> {
                val keyBytes = RecoveryKey.decode(credential.recoveryKey) ?: throw InvalidCredentialsException()
                for (slot in header.slots.filterIsInstance<KeySlot.Recovery>()) {
                    val kek = recoveryKek(keyBytes, slot.salt.decodeBase64())
                    unwrap(header.vaultId, slot, kek)?.let { return VaultKeys(header.vaultId, crypto, it) }
                }
            }
        }
        throw InvalidCredentialsException()
    }

    /**
     * Returns a copy of the raw data key of [keys], for a platform key store to keep behind
     * something other than the password (biometric unlock: the Android Keystore wraps it). The
     * caller owns the copy and must wipe it as soon as it is wrapped; it must never be written
     * anywhere in clear.
     *
     * Also records the key-check value in `vault.key` if the header predates it, so that
     * [unlockWithDataKey] can later tell whether a stored key still belongs to this vault. [keys]
     * came from a password or recovery-key slot, whose AEAD already proved the key is right, so
     * this is the safe moment to write it.
     */
    fun exportDataKey(keys: VaultKeys): ByteArray {
        val header = readHeader()
        require(keys.vaultId == header.vaultId) { "These keys belong to another vault" }
        if (header.keyCheck == null) writeHeader(header.copy(keyCheck = keyCheck(keys.dataKey)))
        return keys.dataKey.copyOf()
    }

    /**
     * Opens the vault with a raw data key previously obtained from [exportDataKey], skipping the
     * key slots. The caller keeps ownership of [dataKey] (a copy goes into the returned keys) and
     * should wipe it.
     *
     * A raw key bypasses the slots' authenticated encryption, so it is checked first against the
     * header's key-check value: a subkey derived from the data key (same KDF and domain separation
     * as the other subkeys, see [VaultKeys]). It reveals nothing about the 256-bit key, and turns a
     * stale or foreign key (a wrapped key left over from a replaced vault, a bug) into a clear
     * [InvalidCredentialsException] here, instead of an unreadable database or attachments
     * encrypted under the wrong key later. Comparing against a stored value, rather than e.g.
     * trying to open the database, keeps the check in the vault, independent of storage. Headers
     * without a key-check value (written before it existed, and never exported since) are refused.
     *
     * @throws InvalidCredentialsException if [dataKey] is not this vault's data key.
     */
    fun unlockWithDataKey(dataKey: ByteArray): VaultKeys {
        val header = readHeader()
        val expected = header.keyCheck?.decodeBase64() ?: throw InvalidCredentialsException()
        if (dataKey.size != Crypto.KEY_BYTES) throw InvalidCredentialsException()
        val actual = keyCheck(dataKey).decodeBase64()
        if (!constantTimeEquals(expected, actual)) throw InvalidCredentialsException()
        return VaultKeys(header.vaultId, crypto, dataKey.copyOf())
    }

    /** Replaces the password. Data stays encrypted with the same data key. */
    fun changePassword(keys: VaultKeys, newPassword: String) {
        val header = readHeader()
        val slots = header.slots.filterNot { it is KeySlot.Password } + passwordSlot(header.vaultId, keys.dataKey, newPassword)
        writeHeader(header.copy(slots = slots))
    }

    /** Creates a new recovery key, invalidating any previous one. */
    fun regenerateRecoveryKey(keys: VaultKeys): String {
        val header = readHeader()
        val (slot, recoveryKey) = recoverySlot(header.vaultId, keys.dataKey)
        writeHeader(header.copy(slots = header.slots.filterNot { it is KeySlot.Recovery } + slot))
        return recoveryKey
    }

    fun removeRecoveryKey() {
        val header = readHeader()
        writeHeader(header.copy(slots = header.slots.filterNot { it is KeySlot.Recovery }))
    }

    private fun passwordSlot(vaultId: String, dataKey: ByteArray, password: String): KeySlot.Password {
        val salt = crypto.randomBytes(Crypto.SALT_BYTES)
        val kek = crypto.argon2id(password, salt, argon2Params)
        return KeySlot.Password(
            salt = salt.toBase64(),
            opsLimit = argon2Params.opsLimit,
            memLimitBytes = argon2Params.memLimitBytes,
            wrappedKey = wrap(vaultId, SLOT_PASSWORD, kek, dataKey),
        )
    }

    private fun recoverySlot(vaultId: String, dataKey: ByteArray): Pair<KeySlot.Recovery, String> {
        val keyBytes = crypto.randomBytes(RecoveryKey.BYTES)
        val salt = crypto.randomBytes(Crypto.SALT_BYTES)
        val kek = recoveryKek(keyBytes, salt)
        val slot = KeySlot.Recovery(salt = salt.toBase64(), wrappedKey = wrap(vaultId, SLOT_RECOVERY, kek, dataKey))
        return slot to RecoveryKey.encode(keyBytes)
    }

    // The recovery key is 160 random bits, so a fast keyed hash is enough: unlike a password,
    // there is nothing for a slow KDF to protect against guessing.
    private fun recoveryKek(keyBytes: ByteArray, salt: ByteArray): ByteArray =
        crypto.keyedHash(key = keyBytes, data = salt + "secrt-recovery".encodeToByteArray())

    private fun wrap(vaultId: String, slotType: String, kek: ByteArray, dataKey: ByteArray): String =
        crypto.seal(kek, dataKey, slotAad(vaultId, slotType)).toBase64().also { crypto.wipe(kek) }

    private fun unwrap(vaultId: String, slot: KeySlot, kek: ByteArray): ByteArray? {
        val type = if (slot is KeySlot.Password) SLOT_PASSWORD else SLOT_RECOVERY
        return try {
            crypto.open(kek, slot.wrappedKey.decodeBase64(), slotAad(vaultId, type))
        } catch (e: DecryptionException) {
            null
        } finally {
            crypto.wipe(kek)
        }
    }

    // Not secret (it's stored in vault.key), so it isn't wiped.
    private fun keyCheck(dataKey: ByteArray): String = crypto.deriveSubkey(dataKey, KEY_CHECK_SUBKEY_ID, "secrtkcv").toBase64()

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun slotAad(vaultId: String, slotType: String) = "secrt-slot:$vaultId:$slotType".encodeToByteArray()

    private fun readHeader(): VaultHeader = parseHeader(headerJson())

    private fun parseHeader(text: String): VaultHeader {
        val header = try {
            SecrtJson.decodeFromString<VaultHeader>(text)
        } catch (e: IllegalArgumentException) {
            throw InvalidVaultHeaderException("Not a valid vault key file", e)
        }
        if (header.format > VaultHeader.CURRENT_FORMAT) {
            throw InvalidVaultHeaderException("Vault format ${header.format} is newer than this app supports")
        }
        return header
    }

    private fun writeHeader(header: VaultHeader) = writeHeaderText(SecrtJson.encodeToString(header))

    private fun writeHeaderText(text: String) {
        val temp = directory / "vault.key.tmp"
        fileSystem.write(temp) { writeUtf8(text) }
        fileSystem.atomicMove(temp, headerPath)
    }

    private companion object {
        const val SLOT_PASSWORD = "password"
        const val SLOT_RECOVERY = "recovery"

        /** Follows the subkey ids used by [VaultKeys] (1-4). */
        const val KEY_CHECK_SUBKEY_ID = 5
    }
}
