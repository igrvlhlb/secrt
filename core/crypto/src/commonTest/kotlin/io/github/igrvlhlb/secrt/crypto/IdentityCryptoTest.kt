package io.github.igrvlhlb.secrt.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Primitives behind device identities and the LAN channel, against published test vectors. */
class IdentityCryptoTest {

    @Test
    fun blake2bMatchesTheRfc7693Vector() = runTest {
        val crypto = SodiumCrypto.create()
        assertContentEquals(
            (
                "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                    "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"
                ).hexToByteArray(),
            crypto.hash("abc".encodeToByteArray(), 64),
        )
    }

    @Test
    fun ed25519MatchesTheRfc8032Vector() = runTest {
        val crypto = SodiumCrypto.create()
        // RFC 8032, section 7.1, TEST 1 (empty message).
        val keys = crypto.signingKeyPairFromSeed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray())
        assertContentEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray(), keys.publicKey)
        val signature = crypto.sign(keys.secretKey, ByteArray(0))
        assertContentEquals(
            (
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555" +
                    "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
                ).hexToByteArray(),
            signature,
        )
        assertTrue(crypto.verifySignature(keys.publicKey, ByteArray(0), signature))
    }

    @Test
    fun signaturesDontVerifyForAnotherMessageKeyOrTamperedSignature() = runTest {
        val crypto = SodiumCrypto.create()
        val keys = crypto.signingKeyPair()
        val other = crypto.signingKeyPair()
        val message = "transcript".encodeToByteArray()
        val signature = crypto.sign(keys.secretKey, message)

        assertTrue(crypto.verifySignature(keys.publicKey, message, signature))
        assertFalse(crypto.verifySignature(keys.publicKey, "transcripT".encodeToByteArray(), signature))
        assertFalse(crypto.verifySignature(other.publicKey, message, signature))
        assertFalse(crypto.verifySignature(keys.publicKey, message, signature.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        // Malformed input is a failed verification, not a crash.
        assertFalse(crypto.verifySignature(keys.publicKey.copyOf(31), message, signature))
        assertFalse(crypto.verifySignature(keys.publicKey, message, signature.copyOf(63)))
    }

    @Test
    fun keyExchangeMatchesRfc7748AndLibsodiumKx() = runTest {
        val crypto = SodiumCrypto.create()
        // RFC 7748, section 6.1: Alice connects (client) to Bob, whose public key she knows.
        val alice = KeyPair(
            publicKey = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hexToByteArray(),
            secretKey = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToByteArray(),
        )
        val bobPublicKey = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToByteArray()
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToByteArray()

        // crypto_kx: rx || tx = BLAKE2b-512(X25519 shared secret || client_pk || server_pk).
        val expected = crypto.hash(shared + alice.publicKey + bobPublicKey, 64)
        val client = crypto.clientSessionKeys(alice, bobPublicKey)
        assertContentEquals(expected.copyOfRange(0, 32), client.receive)
        assertContentEquals(expected.copyOfRange(32, 64), client.send)
    }

    @Test
    fun keyExchangeRejectsALowOrderPublicKey() = runTest {
        val crypto = SodiumCrypto.create()
        assertFailsWith<IllegalArgumentException> { crypto.clientSessionKeys(crypto.keyExchangeKeyPair(), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { crypto.serverSessionKeys(crypto.keyExchangeKeyPair(), ByteArray(32)) }
    }

    @Test
    fun freshKeyExchangePairsAgree() = runTest {
        val crypto = SodiumCrypto.create()
        val a = crypto.keyExchangeKeyPair()
        val b = crypto.keyExchangeKeyPair()
        val client = crypto.clientSessionKeys(a, b.publicKey)
        val server = crypto.serverSessionKeys(b, a.publicKey)
        assertContentEquals(client.send, server.receive)
        assertContentEquals(client.receive, server.send)
        assertFalse(client.send.contentEquals(client.receive))
    }
}
