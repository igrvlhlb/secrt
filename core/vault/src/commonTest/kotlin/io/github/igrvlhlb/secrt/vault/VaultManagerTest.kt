package io.github.igrvlhlb.secrt.vault

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.model.Hlc
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

    private fun stamp(millis: Long, device: String = "phone-id", name: String? = "Phone") = KeySlotsStamp(Hlc(millis, 0, device), name)

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

        manager.changePassword(created, "new", stamp(1))

        assertFailsWith<InvalidCredentialsException> { manager.unlock("old") }
        assertContentEquals(created.databaseKey, manager.unlock("new").databaseKey)
        assertContentEquals(created.databaseKey, manager.unlockWithRecoveryKey(recoveryKey!!).databaseKey)
    }

    @Test
    fun regeneratingRecoveryKeyInvalidatesTheOldOne() = runTest {
        val manager = manager()
        val (created, oldKey) = manager.create("pw", withRecoveryKey = true)

        val newKey = manager.regenerateRecoveryKey(created, stamp(1))

        assertFailsWith<InvalidCredentialsException> { manager.unlockWithRecoveryKey(oldKey!!) }
        assertContentEquals(created.databaseKey, manager.unlockWithRecoveryKey(newKey).databaseKey)

        manager.removeRecoveryKey(created, stamp(2))
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

    @Test
    fun exportedDataKeyUnlocksTheSameVault() = runTest {
        val manager = manager()
        val (created, _) = manager.create("pw", withRecoveryKey = false)

        val dataKey = manager.exportDataKey(created)
        val unlocked = manager.unlockWithDataKey(dataKey)

        assertEquals(created.vaultId, unlocked.vaultId)
        assertContentEquals(created.databaseKey, unlocked.databaseKey)
        // The caller wiping its copy must not affect the opened keys.
        dataKey.fill(0)
        assertContentEquals(created.archiveKey, unlocked.archiveKey)
        unlocked.close()
        assertContentEquals(created.databaseKey, manager.unlock("pw").databaseKey)
    }

    @Test
    fun foreignOrMalformedDataKeyIsRejected() = runTest {
        val manager = manager()
        manager.create("pw", withRecoveryKey = false)
        val other = VaultManager(fileSystem, "/other".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)
        val (otherKeys, _) = other.create("pw", withRecoveryKey = false)

        assertFailsWith<InvalidCredentialsException> { manager.unlockWithDataKey(other.exportDataKey(otherKeys)) }
        assertFailsWith<InvalidCredentialsException> { manager.unlockWithDataKey(ByteArray(32)) }
        assertFailsWith<InvalidCredentialsException> { manager.unlockWithDataKey(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { manager.exportDataKey(otherKeys) }
    }

    @Test
    fun keyCheckIsAddedToHeadersThatPredateIt() = runTest {
        val manager = manager()
        val (created, _) = manager.create("pw", withRecoveryKey = true)
        // A vault.key as written before key-check values existed.
        val legacy = manager.headerJson().replace(Regex(""","keyCheck":"[^"]*""""), "")
        assertFalse("keyCheck" in legacy)
        manager.install(legacy)
        val dataKey = created.dataKey.copyOf()

        assertFailsWith<InvalidCredentialsException> { manager.unlockWithDataKey(dataKey) }

        manager.exportDataKey(manager.unlock("pw"))
        assertContentEquals(created.blobKey, manager.unlockWithDataKey(dataKey).blobKey)
        // Changing the password keeps it.
        manager.changePassword(manager.unlock("pw"), "new", stamp(1))
        assertContentEquals(created.blobKey, manager.unlockWithDataKey(dataKey).blobKey)
    }

    @Test
    fun everyKeySlotChangeIsStampedAndStampsOnlyMoveForward() = runTest {
        val manager = manager()
        val (keys, _) = manager.create("pw", withRecoveryKey = true)
        assertNull(manager.keySlotsStamp(keys))

        manager.changePassword(keys, "new", stamp(1_000))
        assertEquals(KeySlotsStamp(Hlc(1_000, 0, "phone-id"), "Phone"), manager.keySlotsStamp(keys))
        // A clock behind the last change (another device's, adopted) still stamps after it.
        manager.regenerateRecoveryKey(keys, stamp(500, device = "laptop-id", name = "Laptop"))
        assertEquals(KeySlotsStamp(Hlc(1_000, 1, "laptop-id"), "Laptop"), manager.keySlotsStamp(keys))
        manager.removeRecoveryKey(keys, stamp(2_000, name = null))
        assertEquals(KeySlotsStamp(Hlc(2_000, 0, "phone-id"), null), manager.keySlotsStamp(keys))
    }

    @Test
    fun theDeviceNameIsSealedWithTheDiarysKeys() = runTest {
        val manager = manager()
        val (keys, _) = manager.create("pw", withRecoveryKey = false)
        manager.changePassword(keys, "new", stamp(1_000, name = "Igor's phone"))
        val header = manager.headerJson()
        // vault.key travels in clear (backups, exports): the name doesn't.
        assertFalse("Igor" in header)

        val other = VaultManager(fileSystem, "/other".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)
        val (otherKeys, _) = other.create("pw", withRecoveryKey = false)
        assertEquals(KeySlotsStamp(Hlc(1_000, 0, "phone-id"), null), other.keySlotsStamp(otherKeys, header))
        // Moving the sealed name onto another stamp breaks it too.
        val moved = header.replace(Hlc(1_000, 0, "phone-id").encode(), Hlc(9_000, 0, "phone-id").encode())
        assertEquals(KeySlotsStamp(Hlc(9_000, 0, "phone-id"), null), manager.keySlotsStamp(keys, moved))
    }

    @Test
    fun adoptsOnlyHeadersOfThisDiaryAndDataKey() = runTest {
        // Two devices of one diary: the laptop changes the password, the phone takes it on.
        val phone = manager()
        val (keys, _) = phone.create("old", withRecoveryKey = false)
        val laptop = VaultManager(fileSystem, "/laptop".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)
        laptop.install(phone.headerJson())
        laptop.changePassword(laptop.unlock("old"), "new", stamp(1_000, device = "laptop-id", name = "Laptop"))
        val newer = laptop.headerJson()

        phone.verifyExternal(newer, keys)
        phone.adopt(newer, keys)
        assertEquals(newer, phone.headerJson())
        assertFailsWith<InvalidCredentialsException> { phone.unlock("old") }
        assertContentEquals(keys.databaseKey, phone.unlock("new").databaseKey)
        assertEquals("Laptop", phone.keySlotsStamp(keys)?.deviceName)
        // Same data key: a key kept elsewhere (biometric unlock) still opens it.
        assertContentEquals(keys.blobKey, phone.unlockWithDataKey(keys.dataKey.copyOf()).blobKey)

        // Another diary's header, one whose key-check value was swapped for another data key's,
        // one without a key-check value or a password, and garbage: all refused, nothing installed.
        val other = VaultManager(fileSystem, "/other".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)
        val (otherKeys, _) = other.create("new", withRecoveryKey = false)
        other.changePassword(otherKeys, "new", stamp(5_000))
        val otherCheck = Regex(""""keyCheck":"[^"]*"""").find(other.headerJson())!!.value
        val refused = listOf(
            other.headerJson(),
            newer.replace(Regex(""""keyCheck":"[^"]*""""), otherCheck),
            newer.replace(Regex(""","keyCheck":"[^"]*""""), ""),
            newer.replace(Regex(""""type":"password""""), """"type":"recovery""""),
            "{ not json",
        )
        for (header in refused) {
            assertFailsWith<InvalidVaultHeaderException>(header) { phone.adopt(header, keys) }
        }
        assertEquals(newer, phone.headerJson())
    }

    @Test
    fun headersWithoutAStampStillRead() = runTest {
        val manager = manager()
        val (keys, _) = manager.create("pw", withRecoveryKey = false)
        manager.changePassword(keys, "new", stamp(1_000))
        // As written (or rewritten) by a version from before stamps.
        val legacy = manager.headerJson().replace(Regex(""","slotsChanged":\{[^}]*\}"""), "")
        assertFalse("slotsChanged" in legacy)
        manager.install(legacy)

        assertNull(manager.keySlotsStamp(keys))
        assertContentEquals(keys.databaseKey, manager.unlock("new").databaseKey)
    }
}
