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
        writeHeader(VaultHeader(vaultId = vaultId, slots = slots))
        return VaultKeys(vaultId, crypto, dataKey) to recoveryKey
    }

    /** @throws InvalidCredentialsException */
    fun unlock(password: String): VaultKeys {
        val header = readHeader()
        for (slot in header.slots.filterIsInstance<KeySlot.Password>()) {
            val params = Argon2Params(slot.opsLimit, slot.memLimitBytes)
            val kek = crypto.argon2id(password, slot.salt.decodeBase64(), params)
            unwrap(header.vaultId, slot, kek)?.let { return VaultKeys(header.vaultId, crypto, it) }
        }
        throw InvalidCredentialsException()
    }

    /** @throws InvalidCredentialsException */
    fun unlockWithRecoveryKey(recoveryKey: String): VaultKeys {
        val header = readHeader()
        val keyBytes = RecoveryKey.decode(recoveryKey) ?: throw InvalidCredentialsException()
        for (slot in header.slots.filterIsInstance<KeySlot.Recovery>()) {
            val kek = recoveryKek(keyBytes, slot.salt.decodeBase64())
            unwrap(header.vaultId, slot, kek)?.let { return VaultKeys(header.vaultId, crypto, it) }
        }
        throw InvalidCredentialsException()
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

    private fun slotAad(vaultId: String, slotType: String) = "secrt-slot:$vaultId:$slotType".encodeToByteArray()

    private fun readHeader(): VaultHeader {
        val text = fileSystem.read(headerPath) { readUtf8() }
        val header = SecrtJson.decodeFromString<VaultHeader>(text)
        check(header.format <= VaultHeader.CURRENT_FORMAT) { "Vault format ${header.format} is newer than this app supports" }
        return header
    }

    private fun writeHeader(header: VaultHeader) {
        val temp = directory / "vault.key.tmp"
        fileSystem.write(temp) { writeUtf8(SecrtJson.encodeToString(header)) }
        fileSystem.atomicMove(temp, headerPath)
    }

    private companion object {
        const val SLOT_PASSWORD = "password"
        const val SLOT_RECOVERY = "recovery"
    }
}
