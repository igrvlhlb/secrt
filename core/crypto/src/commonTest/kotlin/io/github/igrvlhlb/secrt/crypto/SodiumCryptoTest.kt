package io.github.igrvlhlb.secrt.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SodiumCryptoTest {

    @Test
    fun sealAndOpenRoundTrip() = runTest {
        val crypto = SodiumCrypto.create()
        val key = crypto.randomBytes(Crypto.KEY_BYTES)
        val sealed = crypto.seal(key, "dear diary".encodeToByteArray(), "aad".encodeToByteArray())

        assertContentEquals("dear diary".encodeToByteArray(), crypto.open(key, sealed, "aad".encodeToByteArray()))
    }

    @Test
    fun openRejectsWrongKeyTamperingAndWrongAssociatedData() = runTest {
        val crypto = SodiumCrypto.create()
        val key = crypto.randomBytes(Crypto.KEY_BYTES)
        val aad = "aad".encodeToByteArray()
        val sealed = crypto.seal(key, "secret".encodeToByteArray(), aad)

        assertFailsWith<DecryptionException> { crypto.open(crypto.randomBytes(Crypto.KEY_BYTES), sealed, aad) }
        assertFailsWith<DecryptionException> { crypto.open(key, sealed, "other".encodeToByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<DecryptionException> { crypto.open(key, tampered, aad) }
    }

    @Test
    fun argon2idIsDeterministicPerSaltAndPassword() = runTest {
        val crypto = SodiumCrypto.create()
        val salt = crypto.randomBytes(Crypto.SALT_BYTES)

        val a = crypto.argon2id("correct horse", salt, Argon2Params.Insecure)
        val b = crypto.argon2id("correct horse", salt, Argon2Params.Insecure)
        val c = crypto.argon2id("wrong horse", salt, Argon2Params.Insecure)

        assertContentEquals(a, b)
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun subkeysDifferByIdAndContext() = runTest {
        val crypto = SodiumCrypto.create()
        val master = crypto.randomBytes(Crypto.KEY_BYTES)

        val a = crypto.deriveSubkey(master, 1, "context1")
        assertContentEquals(a, crypto.deriveSubkey(master, 1, "context1"))
        assertFalse(a.contentEquals(crypto.deriveSubkey(master, 2, "context1")))
        assertFalse(a.contentEquals(crypto.deriveSubkey(master, 1, "context2")))
    }
}
