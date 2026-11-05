package io.github.igrvlhlb.secrt.vault

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultManagerTest {

    private val fileSystem = FakeFileSystem()

    private suspend fun manager() =
        VaultManager(fileSystem, "/vault".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)

    @Test
    fun createThenUnlockWithPasswordYieldsSameKeys() = runTest {
        val manager = manager()
        val (created, _) = manager.create("hunter2", withRecoveryKey = false)

        val unlocked = manager.unlock("hunter2")

        assertEquals(created.vaultId, unlocked.vaultId)
        assertContentEquals(created.databaseKey, unlocked.databaseKey)
        assertContentEquals(created.blobKey, unlocked.blobKey)
    }

    @Test
    fun wrongPasswordIsRejected() = runTest {
        val manager = manager()
        manager.create("hunter2", withRecoveryKey = false)

        assertFailsWith<InvalidCredentialsException> { manager.unlock("hunter3") }
    }

    @Test
    fun recoveryKeyUnlocksAndIsForgivingAboutFormatting() = runTest {
        val manager = manager()
        val (created, recoveryKey) = manager.create("hunter2", withRecoveryKey = true)
        assertNotNull(recoveryKey)
        assertTrue(manager.hasRecoveryKey())

        val sloppy = recoveryKey.lowercase().replace("-", " ").replace('0', 'o')
        val unlocked = manager.unlockWithRecoveryKey(sloppy)

        assertContentEquals(created.databaseKey, unlocked.databaseKey)
        assertFailsWith<InvalidCredentialsException> { manager.unlockWithRecoveryKey("AAAA-BBBB") }
    }

    @Test
    fun noRecoveryKeyWhenSkipped() = runTest {
        val manager = manager()
        val (_, recoveryKey) = manager.create("hunter2", withRecoveryKey = false)

        assertNull(recoveryKey)
        assertFalse(manager.hasRecoveryKey())
    }

    @Test
    fun changePasswordKeepsDataKey() = runTest {
        val manager = manager()
        val (created, recoveryKey) = manager.create("old", withRecoveryKey = true)

        manager.changePassword(created, "new")

        assertFailsWith<InvalidCredentialsException> { manager.unlock("old") }
        assertContentEquals(created.databaseKey, manager.unlock("new").databaseKey)
        assertContentEquals(created.databaseKey, manager.unlockWithRecoveryKey(recoveryKey!!).databaseKey)
    }

    @Test
    fun regeneratingRecoveryKeyInvalidatesTheOldOne() = runTest {
        val manager = manager()
        val (created, oldKey) = manager.create("pw", withRecoveryKey = true)

        val newKey = manager.regenerateRecoveryKey(created)

        assertFailsWith<InvalidCredentialsException> { manager.unlockWithRecoveryKey(oldKey!!) }
        assertContentEquals(created.databaseKey, manager.unlockWithRecoveryKey(newKey).databaseKey)

        manager.removeRecoveryKey()
        assertFailsWith<InvalidCredentialsException> { manager.unlockWithRecoveryKey(newKey) }
    }

    @Test
    fun externalHeaderUnlocksAndInstallsElsewhere() = runTest {
        val source = manager()
        val (created, recoveryKey) = source.create("pw", withRecoveryKey = true)
        val header = source.headerJson()
        val target = VaultManager(fileSystem, "/other".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)

        assertFailsWith<InvalidCredentialsException> { target.unlockExternal(header, Credential.Password("nope")) }
        assertFailsWith<InvalidVaultHeaderException> { target.unlockExternal("{ not json", Credential.Password("pw")) }
        assertContentEquals(created.archiveKey, target.unlockExternal(header, Credential.RecoveryKey(recoveryKey!!)).archiveKey)
        assertFalse(target.exists())

        target.install(header)
        assertContentEquals(created.databaseKey, target.unlock("pw").databaseKey)
        target.delete()
        assertFalse(target.exists())
    }

    @Test
    fun recoveryKeyEncodingRoundTrips() = runTest {
        val bytes = SodiumCrypto.create().randomBytes(RecoveryKey.BYTES)
        val text = RecoveryKey.encode(bytes)

        assertEquals(39, text.length) // 32 symbols + 7 dashes
        assertContentEquals(bytes, RecoveryKey.decode(text))
    }
}
