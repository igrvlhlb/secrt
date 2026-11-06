package io.github.igrvlhlb.secrt.lan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingInviteTest {

    private val key = ByteArray(32) { it.toByte() }
    private val secret = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun roundTripsThroughItsLink() {
        val link = PairingInvite(listOf("192.168.15.4", "10.0.0.255"), 47820, key, secret).toLink()
        assertTrue(link.startsWith("secrt-pair:"))
        // Small enough for a QR code that scans easily, and nothing a shell or URL would mangle.
        assertTrue(link.length < 120, link)
        assertTrue(link.all { it.isLetterOrDigit() || it in "-_:" }, link)

        val parsed = PairingInvite.parse(" $link\n")
        assertEquals(listOf("192.168.15.4", "10.0.0.255"), parsed.addresses)
        assertEquals(47820, parsed.port)
        assertContentEquals(key, parsed.identityKey)
        assertContentEquals(secret, parsed.secret)
    }

    @Test
    fun rejectsAnythingElse() {
        val valid = PairingInvite(listOf("192.168.15.4"), 47820, key, secret).toLink()
        for (text in listOf("", "https://example.com", "secrt-pair:", "secrt-pair:!!!", valid.dropLast(2), valid + "AA")) {
            assertFailsWith<LanException.InvalidCode>(text) { PairingInvite.parse(text) }
        }
    }

    @Test
    fun acceptsOnlyIpv4Addresses() {
        for (address in listOf("256.1.1.1", "1.2.3", "fe80::1", "host.local", "1.2.3.-4")) {
            assertFailsWith<IllegalArgumentException>(address) { PairingInvite(listOf(address), 1, key, secret) }
        }
    }
}
