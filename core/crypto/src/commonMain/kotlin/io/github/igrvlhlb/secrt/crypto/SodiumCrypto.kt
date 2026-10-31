package io.github.igrvlhlb.secrt.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil

/** [Crypto] backed by libsodium. Obtain it with [create], which loads the native library once. */
class SodiumCrypto private constructor() : Crypto {

    override fun randomBytes(size: Int): ByteArray = LibsodiumRandom.buf(size).asByteArray()

    override fun argon2id(password: String, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray =
        PasswordHash.pwhash(
            outputLength = outputLength,
            password = password,
            salt = salt.asUByteArray(),
            opsLimit = params.opsLimit.toULong(),
            memLimit = params.memLimitBytes,
            algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
        ).asByteArray()

    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            message = plaintext.asUByteArray(),
            associatedData = associatedData.asUByteArray(),
            nonce = nonce.asUByteArray(),
            key = key.asUByteArray(),
        ).asByteArray()

    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = ciphertext.asUByteArray(),
                associatedData = associatedData.asUByteArray(),
                nonce = nonce.asUByteArray(),
                key = key.asUByteArray(),
            ).asByteArray()
        } catch (e: AeadCorrupedOrTamperedDataException) {
            throw DecryptionException()
        }

    override fun deriveSubkey(masterKey: ByteArray, subkeyId: Int, context: String, length: Int): ByteArray {
        require(context.length == 8) { "KDF context must be exactly 8 characters" }
        return Kdf.deriveFromKey(subkeyId.toUInt(), length, context, masterKey.asUByteArray()).asByteArray()
    }

    override fun keyedHash(key: ByteArray, data: ByteArray, outputLength: Int): ByteArray =
        GenericHash.genericHash(data.asUByteArray(), outputLength, key.asUByteArray()).asByteArray()

    override fun wipe(bytes: ByteArray) = LibsodiumUtil.memzero(bytes.asUByteArray())

    companion object {
        suspend fun create(): SodiumCrypto {
            if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
            return SodiumCrypto()
        }
    }
}
