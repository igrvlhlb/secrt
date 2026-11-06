package io.github.igrvlhlb.secrt.crypto

import com.ionspin.kotlin.crypto.GeneralLibsodiumException
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.keyexchange.KeyExchange
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.signature.InvalidSignatureException
import com.ionspin.kotlin.crypto.signature.Signature
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

    override fun hash(data: ByteArray, outputLength: Int): ByteArray =
        GenericHash.genericHash(data.asUByteArray(), outputLength).asByteArray()

    override fun signingKeyPair(): KeyPair = Signature.keypair().let { KeyPair(it.publicKey.asByteArray(), it.secretKey.asByteArray()) }

    override fun signingKeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32) { "Ed25519 seeds are 32 bytes" }
        return Signature.seedKeypair(seed.asUByteArray()).let { KeyPair(it.publicKey.asByteArray(), it.secretKey.asByteArray()) }
    }

    override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray {
        require(secretKey.size == 64) { "Ed25519 secret keys are 64 bytes" }
        return Signature.detached(message.asUByteArray(), secretKey.asUByteArray()).asByteArray()
    }

    override fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Crypto.PUBLIC_KEY_BYTES || signature.size != Crypto.SIGNATURE_BYTES) return false
        return try {
            Signature.verifyDetached(signature.asUByteArray(), message.asUByteArray(), publicKey.asUByteArray())
            true
        } catch (e: InvalidSignatureException) {
            false
        }
    }

    override fun keyExchangeKeyPair(): KeyPair = KeyExchange.keypair().let { KeyPair(it.publicKey.asByteArray(), it.secretKey.asByteArray()) }

    override fun clientSessionKeys(clientKeyPair: KeyPair, serverPublicKey: ByteArray): SessionKeys {
        require(serverPublicKey.size == Crypto.PUBLIC_KEY_BYTES) { "X25519 public keys are 32 bytes" }
        // libsodium rejects low-order points (all-zero shared secret) with an error.
        val keys = try {
            KeyExchange.clientSessionKeys(clientKeyPair.publicKey.asUByteArray(), clientKeyPair.secretKey.asUByteArray(), serverPublicKey.asUByteArray())
        } catch (e: GeneralLibsodiumException) {
            throw IllegalArgumentException("Unusable public key", e)
        }
        return SessionKeys(receive = keys.receiveKey.asByteArray(), send = keys.sendKey.asByteArray())
    }

    override fun serverSessionKeys(serverKeyPair: KeyPair, clientPublicKey: ByteArray): SessionKeys {
        require(clientPublicKey.size == Crypto.PUBLIC_KEY_BYTES) { "X25519 public keys are 32 bytes" }
        val keys = try {
            KeyExchange.serverSessionKeys(serverKeyPair.publicKey.asUByteArray(), serverKeyPair.secretKey.asUByteArray(), clientPublicKey.asUByteArray())
        } catch (e: GeneralLibsodiumException) {
            throw IllegalArgumentException("Unusable public key", e)
        }
        return SessionKeys(receive = keys.receiveKey.asByteArray(), send = keys.sendKey.asByteArray())
    }

    override fun wipe(bytes: ByteArray) = LibsodiumUtil.memzero(bytes.asUByteArray())

    companion object {
        suspend fun create(): SodiumCrypto {
            if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
            return SodiumCrypto()
        }
    }
}
