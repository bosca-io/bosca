package bosca.collaboration.graphql

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FederationPeerControllerTest {
    @Test fun `peer rollup returns only that peer's links and linked peer lookup uses its service`() = runTest {
        val service = mockk<FederationService>()
        val peer = FederationPeer(UUID.parse("11111111-1111-1111-1111-111111111111"), "Peer", "nats://peer:4222", "https://peer.example.test")
        val channel = UUID.parse("22222222-2222-2222-2222-222222222222")
        val remote = UUID.parse("33333333-3333-3333-3333-333333333333")
        val other = UUID.parse("44444444-4444-4444-4444-444444444444")
        val link = FederatedChannel(channel, peer.id, remote, FederationSyncDirection.INBOUND)
        coEvery { service.getAllFederatedChannels() } returns listOf(link, link.copy(peerId = other))
        coEvery { service.getPeer(peer.id) } returns peer
        assertEquals(listOf(link), FederationPeerController(service).federatedChannels(peer))
        assertEquals(peer, FederatedChannelController(service).peer(link))
        coVerify(exactly = 1) { service.getPeer(peer.id) }
    }
}
