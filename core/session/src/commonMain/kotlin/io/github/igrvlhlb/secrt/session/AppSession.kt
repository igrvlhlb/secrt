package io.github.igrvlhlb.secrt.session

import io.github.igrvlhlb.secrt.archive.DifferentVaultException
import io.github.igrvlhlb.secrt.archive.SecrtArchive
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.storage.BlobStore
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import io.github.igrvlhlb.secrt.vault.VaultKeys
import io.github.igrvlhlb.secrt.vault.VaultManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

sealed interface SessionState {
    /** First run: no vault yet. */
    data object NoVault : SessionState

    data object Locked : SessionState

    data class Unlocked(
        val store: DiaryStore,
        /** The user got in with the recovery key and should pick a new password. */
        val viaRecoveryKey: Boolean,
    ) : SessionState
}

/**
 * Owns the vault's lifecycle: creating it, unlocking it into an open [DiaryStore], and locking it
 * again (closing the database and wiping keys from memory).
 */
class AppSession(
    private val fileSystem: FileSystem,
    private val vaultDirectory: Path,
    private val driverFactory: DriverFactory,
    private val cryptoProvider: suspend () -> Crypto,
    private val ioDispatcher: CoroutineDispatcher,
    private val argon2Params: Argon2Params = Argon2Params.Default,
) {
    private val lock = Mutex()
    private var keys: VaultKeys? = null
    private var blobs: BlobStore? = null
    private var vaultManager: VaultManager? = null

    private val _state = MutableStateFlow(
        if (fileSystem.exists(vaultDirectory / "vault.key")) SessionState.Locked else SessionState.NoVault,
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Creates the vault and unlocks it. @return the recovery key to show once, if requested. */
    suspend fun create(password: String, withRecoveryKey: Boolean): String? = io {
        wipeLocalData()
        val (newKeys, recoveryKey) = vault().create(password, withRecoveryKey)
        open(newKeys, viaRecoveryKey = false)
        recoveryKey
    }

    /** @throws InvalidCredentialsException */
    suspend fun unlock(password: String) = io {
        open(vault().unlock(password), viaRecoveryKey = false)
    }

    /** @throws InvalidCredentialsException */
    suspend fun unlockWithRecoveryKey(recoveryKey: String) = io {
        open(vault().unlockWithRecoveryKey(recoveryKey), viaRecoveryKey = true)
    }

    suspend fun lock() = io {
        (_state.value as? SessionState.Unlocked)?.store?.close()
        keys?.close()
        keys = null
        blobs = null
        if (_state.value is SessionState.Unlocked) _state.value = SessionState.Locked
    }

    /**
     * @param currentPassword required, except right after unlocking with the recovery key.
     * @throws InvalidCredentialsException if [currentPassword] is wrong.
     */
    suspend fun changePassword(currentPassword: String?, newPassword: String) = io {
        val state = _state.value as? SessionState.Unlocked ?: error("Diary is locked")
        val keys = requireNotNull(keys)
        if (!state.viaRecoveryKey) {
            vault().unlock(requireNotNull(currentPassword) { "Current password required" }).close()
        }
        vault().changePassword(keys, newPassword)
        _state.value = state.copy(viaRecoveryKey = false)
    }

    suspend fun hasRecoveryKey(): Boolean = io { vault().hasRecoveryKey() }

    /** Replaces the recovery key. @return the new key, to show once. */
    suspend fun regenerateRecoveryKey(): String = io { vault().regenerateRecoveryKey(requireNotNull(keys) { "Diary is locked" }) }

    suspend fun removeRecoveryKey() = io { vault().removeRecoveryKey() }

    /**
     * Writes an encrypted backup of the whole diary (a `.secrt` file) to [sink], and closes it.
     * It opens with the current password or recovery key.
     */
    suspend fun exportTo(sink: Sink) = io {
        val store = unlockedStore()
        val revisions = store.revisionsSince(emptyMap())
        sink.buffer().use { out ->
            SecrtArchive.write(out, cryptoProvider(), requireNotNull(keys), vault().headerJson(), revisions, requireNotNull(blobs)::readEncrypted)
        }
    }

    /**
     * First run only: recreates the diary from a backup, which then opens with the backup's
     * password or recovery key. Nothing is left behind if it fails.
     *
     * @throws InvalidCredentialsException
     * @throws io.github.igrvlhlb.secrt.archive.ArchiveException if the file isn't a usable backup.
     */
    suspend fun restore(source: Source, credential: Credential) = io {
        check(_state.value == SessionState.NoVault) { "A diary already exists" }
        wipeLocalData()
        val vault = vault()
        val crypto = cryptoProvider()
        var restoredKeys: VaultKeys? = null
        var headerJson: String? = null
        var blobStore: BlobStore? = null
        var store: DiaryStore? = null
        try {
            val revisions = source.buffer().use { input ->
                SecrtArchive.read(
                    input,
                    crypto,
                    unlock = { _, json ->
                        vault.unlockExternal(json, credential).also {
                            restoredKeys = it
                            headerJson = json
                            blobStore = BlobStore(fileSystem, vaultDirectory / "blobs", crypto, it)
                        }
                    },
                    storeBlob = { id, data -> requireNotNull(blobStore).putEncrypted(id, data) },
                )
            }
            val newKeys = requireNotNull(restoredKeys)
            store = DiaryStore.open(driverFactory.create(SecrtDatabase.Schema, newKeys.databaseKey), requireNotNull(blobStore), ioDispatcher)
            store.applyRemote(revisions)
            // Installed last: until vault.key exists the app still considers itself "first run".
            vault.install(requireNotNull(headerJson))
            keys = newKeys
            blobs = blobStore
            _state.value = SessionState.Unlocked(store, viaRecoveryKey = credential is Credential.RecoveryKey)
        } catch (e: Exception) {
            store?.close()
            restoredKeys?.close()
            wipeLocalData()
            throw e
        }
    }

    /**
     * Merges a backup of this same diary (e.g. made on another device) into it.
     * @return how many changes were new.
     * @throws DifferentVaultException if the backup is of another diary.
     */
    suspend fun importFrom(source: Source): Int = io {
        val store = unlockedStore()
        val keys = requireNotNull(keys)
        val blobs = requireNotNull(blobs)
        val revisions = source.buffer().use { input ->
            SecrtArchive.read(
                input,
                cryptoProvider(),
                unlock = { vaultId, _ -> if (vaultId == keys.vaultId) keys else throw DifferentVaultException() },
                storeBlob = blobs::putEncrypted,
            )
        }
        store.applyRemote(revisions)
    }

    private fun unlockedStore(): DiaryStore = (_state.value as? SessionState.Unlocked ?: error("Diary is locked")).store

    /** Removes everything a vault leaves on disk. Only valid while there is no usable vault. */
    private suspend fun wipeLocalData() {
        driverFactory.delete()
        fileSystem.deleteRecursively(vaultDirectory / "blobs", mustExist = false)
        vault().delete()
    }

    private suspend fun vault(): VaultManager =
        vaultManager ?: VaultManager(fileSystem, vaultDirectory, cryptoProvider(), argon2Params).also { vaultManager = it }

    private suspend fun open(newKeys: VaultKeys, viaRecoveryKey: Boolean) {
        val driver = driverFactory.create(SecrtDatabase.Schema, newKeys.databaseKey)
        val blobs = BlobStore(fileSystem, vaultDirectory / "blobs", cryptoProvider(), newKeys)
        val store = try {
            DiaryStore.open(driver, blobs, ioDispatcher)
        } catch (e: Exception) {
            driver.close()
            newKeys.close()
            throw e
        }
        keys = newKeys
        this.blobs = blobs
        _state.value = SessionState.Unlocked(store, viaRecoveryKey)
    }

    private suspend fun <T> io(block: suspend () -> T): T = lock.withLock { withContext(ioDispatcher) { block() } }
}
