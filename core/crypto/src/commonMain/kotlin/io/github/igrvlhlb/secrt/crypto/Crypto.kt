package io.github.igrvlhlb.secrt.crypto

/**
 * The cryptographic primitives the app relies on. Everything above this interface is
 * platform-independent; [SodiumCrypto] implements it with libsodium on every platform.
 */
interface Crypto {

    fun randomBytes(size: Int): ByteArray

    /** Argon2id password hashing, used to turn a password into a key-encryption key. */
    fun argon2id(password: String, salt: ByteArray, params: Argon2Params, outputLength: Int = KEY_BYTES): ByteArray

    /** XChaCha20-Poly1305 encryption. Returns ciphertext followed by the authentication tag. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** @throws DecryptionException if the key is wrong or the data was tampered with. */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray

    /** Derives an independent subkey from [masterKey]; [context] must be exactly 8 characters. */
    fun deriveSubkey(masterKey: ByteArray, subkeyId: Int, context: String, length: Int = KEY_BYTES): ByteArray

    /** Keyed BLAKE2b. */
    fun keyedHash(key: ByteArray, data: ByteArray, outputLength: Int = KEY_BYTES): ByteArray

    /** Unkeyed BLAKE2b, for hashing protocol transcripts. */
    fun hash(data: ByteArray, outputLength: Int = KEY_BYTES): ByteArray

    // Device identities and the LAN channel (see docs/architecture.md, "LAN pairing & sync").

    /** A new random Ed25519 key pair: a device's long-term identity. */
    fun signingKeyPair(): KeyPair

    /** The Ed25519 key pair for a 32-byte [seed]; for known-answer tests and restoring stored keys. */
    fun signingKeyPairFromSeed(seed: ByteArray): KeyPair

    /** Detached Ed25519 signature ([SIGNATURE_BYTES]) of [message] with a 64-byte secret key. */
    fun sign(secretKey: ByteArray, message: ByteArray): ByteArray

    /** Whether [signature] is a valid Ed25519 signature of [message] by [publicKey]. Never throws. */
    fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** A new random X25519 key pair, used once per connection for forward secrecy. */
    fun keyExchangeKeyPair(): KeyPair

    /**
     * libsodium `crypto_kx` session keys of the connecting side: both are derived from the X25519
     * shared secret and both public keys. The client's [SessionKeys.send] is the server's
     * [SessionKeys.receive] and vice versa.
     *
     * @throws IllegalArgumentException if [serverPublicKey] is a weak point (an all-zero shared secret).
     */
    fun clientSessionKeys(clientKeyPair: KeyPair, serverPublicKey: ByteArray): SessionKeys

    /** The listening side's counterpart of [clientSessionKeys]. */
    fun serverSessionKeys(serverKeyPair: KeyPair, clientPublicKey: ByteArray): SessionKeys

    /** Overwrites [bytes] with zeros. */
    fun wipe(bytes: ByteArray)

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val SALT_BYTES = 16
        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64

        /** Size of the authentication tag [aeadEncrypt] appends. */
        const val AEAD_TAG_BYTES = 16
    }
}

/** A public/secret key pair. Ed25519 secret keys are 64 bytes (seed + public key), X25519 ones 32. */
class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** Directional keys of one connection, from [Crypto.clientSessionKeys] / [Crypto.serverSessionKeys]. */
class SessionKeys(val receive: ByteArray, val send: ByteArray)

data class Argon2Params(val opsLimit: Long, val memLimitBytes: Int) {
    companion object {
        /** Tuned for phones: roughly half a second on a mid-range device. */
        val Default = Argon2Params(opsLimit = 3, memLimitBytes = 64 * 1024 * 1024)

        /** Weakest parameters libsodium accepts. Tests only. */
        val Insecure = Argon2Params(opsLimit = 1, memLimitBytes = 8 * 1024)
    }
}

class DecryptionException(message: String = "Decryption failed: wrong key or corrupted data") : Exception(message)

/** Encrypts with a fresh random nonce and returns `nonce || ciphertext`. */
fun Crypto.seal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
    val nonce = randomBytes(Crypto.NONCE_BYTES)
    return nonce + aeadEncrypt(key, nonce, plaintext, associatedData)
}

/** Reverses [seal]. @throws DecryptionException */
fun Crypto.open(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray {
    if (sealed.size < Crypto.NONCE_BYTES) throw DecryptionException("Sealed data is truncated")
    val nonce = sealed.copyOfRange(0, Crypto.NONCE_BYTES)
    return aeadDecrypt(key, nonce, sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size), associatedData)
}
