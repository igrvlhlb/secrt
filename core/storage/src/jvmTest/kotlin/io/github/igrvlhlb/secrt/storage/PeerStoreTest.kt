package io.github.igrvlhlb.secrt.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerStoreTest {

    @Test
    fun pairingAgainRefreshesAPeerButKeepsItsSyncHistory() = runTest {
        val peers = TestReplica.create().store.peers
        peers.pair("key-a", "Galaxy", deviceId = "dev-a", addresses = emptyList(), now = 100)
        peers.recordSync("key-a", "Galaxy S20", deviceId = null, addresses = listOf("10.0.0.2:1"), versionVector = mapOf("dev-a" to 3L), now = 200)

        peers.pair("key-a", "Phone", deviceId = null, addresses = listOf("10.0.0.3:1"), now = 300)

        val peer = peers.observe().first().single()
        assertEquals("Phone", peer.name)
        assertEquals("dev-a", peer.deviceId)
        assertEquals(100, peer.pairedAt)
        assertEquals(200, peer.lastSyncAt)
        assertEquals(listOf("10.0.0.3:1"), peer.addresses)
        assertEquals(mapOf("dev-a" to 3L), peer.versionVector)
    }

    @Test
    fun forgottenPeersAreGoneAndLateSyncRecordsDontBringThemBack() = runTest {
        val peers = TestReplica.create().store.peers
        peers.pair("key-a", "Mac", deviceId = null, addresses = listOf("192.168.1.2:47820"), now = 1)
        peers.forget("key-a")
        peers.recordSync("key-a", "Mac", null, emptyList(), emptyMap(), now = 2)

        assertNull(peers.get("key-a"))
        assertEquals(emptyList(), peers.all())
    }

    @Test
    fun identityIsCreatedOnceAndCanBeReplaced() = runTest {
        val peers = TestReplica.create().store.peers
        val first = peers.identitySeed { ByteArray(32) { 1 } }
        assertContentEquals(first, peers.identitySeed { ByteArray(32) { 2 } })

        peers.setIdentitySeed(ByteArray(32) { 3 })
        assertContentEquals(ByteArray(32) { 3 }, peers.identitySeed { ByteArray(32) { 4 } })
    }
}
