package bosca.collaboration.service

import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationSyncDirection
import bosca.collaboration.federation.LocalPeerIdProvider
import bosca.collaboration.repository.FederationRepository
import bosca.configuration.model.Configuration as ConfigurationRow
import bosca.configuration.service.ConfigurationService
import bosca.di.ObjectProvider
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for the federation handshake — both the outbound POST
 * (FederationServiceImpl.handshakeWithPeer) and the inbound recording
 * (FederationServiceImpl.acceptHandshakeFromPeer).
 *
 * The outbound side is exercised against a real [OkHttpClient] pointed at a
 * [MockWebServer] so the path that builds the URL, attaches the bearer
 * token, and serializes the body is real production code.
 */
class FederationHandshakeTest {

    private val repository = mockk<FederationRepository>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)
    private val profileService = mockk<ObjectProvider<ProfileService>>()
    private val inboundListener = mockk<ObjectProvider<FederationInboundListener>>()
    private val localPeerIdProvider = mockk<LocalPeerIdProvider>()

    private val peerId = UUID.parse("11111111-2222-3333-4444-555555555555")
    private val localChannelId = UUID.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val remoteChannelId = UUID.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val originPeerId = UUID.parse("99999999-8888-7777-6666-555555555555")

    private lateinit var service: FederationServiceImpl
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        coEvery { localPeerIdProvider.get() } returns originPeerId
        // No-op listener refresh — federateChannel calls inboundListener.get().refresh()
        val listener = mockk<FederationInboundListener>(relaxed = true)
        coEvery { inboundListener.get() } returns listener

        server = MockWebServer().apply { start() }
        service = FederationServiceImpl(
            repository = repository,
            configurationService = configurationService,
            profileService = profileService,
            inboundListener = inboundListener,
            localPeerIdProvider = localPeerIdProvider,
        )
        // Trim timeouts so failure cases don't pay the production 5/10s waits.
        service.withHttpClientForTesting(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @AfterTest
    fun teardown() {
        if (::server.isInitialized) server.close()
    }

    private fun mockSecret(secret: String?) {
        if (secret == null) {
            coEvery { configurationService.getByKey(any()) } returns null
            return
        }
        val cfg = mockk<ConfigurationRow>(relaxed = true)
        val cfgId = UUID.random()
        coEvery { cfg.id } returns cfgId
        coEvery { configurationService.getByKey("federation.peer.$peerId.secret") } returns cfg
        coEvery { configurationService.getValue(cfgId) } returns buildJsonObject {
            put("secret", JsonPrimitive(secret))
        }
    }

    /** Peer that points at the local MockWebServer so handshake URLs resolve. */
    private fun makePeer(
        id: UUID = peerId,
        active: Boolean = true,
        baseUrl: String = server.url("/").toString().trimEnd('/'),
    ) = FederationPeer(
        id = id,
        name = "Peer-$id",
        natsUrl = "nats://peer.example.com:4222",
        apiUrl = baseUrl,
        active = active,
    )

    // ---- handshakeWithPeer ---------------------------------------------

    @Test
    fun `handshakeWithPeer posts to the peer's apiUrl with bearer token and JSON body`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer()
        mockSecret("super-secret")
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertTrue(result)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals(FederationServiceImpl.HANDSHAKE_PATH, req.url.encodedPath)
        assertEquals("Bearer super-secret", req.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", req.headers["Content-Type"]?.lowercase())

        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(req.body!!.utf8()) as JsonObject
        assertEquals(originPeerId.toString(), parsed["originPeerId"]?.jsonPrimitive?.content)
        assertEquals(localChannelId.toString(), parsed["originChannelId"]?.jsonPrimitive?.content)
        assertEquals(remoteChannelId.toString(), parsed["remoteChannelId"]?.jsonPrimitive?.content)
        assertEquals("BIDIRECTIONAL", parsed["syncDirection"]?.jsonPrimitive?.content)
    }

    @Test
    fun `handshakeWithPeer records the local link only after the peer accepts`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer()
        mockSecret("super-secret")
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.OUTBOUND)

        coVerify(exactly = 1) {
            repository.upsertFederatedChannel(
                localChannelId,
                peerId,
                remoteChannelId,
                FederationSyncDirection.OUTBOUND,
            )
        }
    }

    @Test
    fun `handshakeWithPeer returns false and skips local recording when the peer responds non-2xx`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer()
        mockSecret("super-secret")
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"bad-secret"}""").build())

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFederatedChannel(any(), any(), any(), any()) }
    }

    @Test
    fun `handshakeWithPeer returns false when the peer is unknown without making a network call`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns null

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertFalse(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `handshakeWithPeer returns false when the peer is inactive`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer(active = false)

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertFalse(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `handshakeWithPeer returns false when no shared secret is configured`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer()
        mockSecret(null) // no secret in config

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertFalse(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `handshakeWithPeer returns false when the HTTP call throws and never records the link`() = runBlocking {
        coEvery { repository.getPeer(peerId) } returns makePeer()
        mockSecret("super-secret")
        // Install an OkHttp client whose interceptor unconditionally throws,
        // simulating a connection error to the peer. Caught upstream so the
        // method returns false rather than propagating.
        val throwingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { _ -> throw IOException("boom") })
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        service.withHttpClientForTesting(throwingClient)

        val result = service.handshakeWithPeer(peerId, localChannelId, remoteChannelId, FederationSyncDirection.BIDIRECTIONAL)

        assertFalse(result)
        coVerify(exactly = 0) { repository.upsertFederatedChannel(any(), any(), any(), any()) }
    }

    // ---- acceptHandshakeFromPeer ---------------------------------------
    //
    // Naming convention in these tests: "originChannelId" is the channel id
    // on the *peer's* side (they call it local); "remoteChannelId" is the
    // channel id on *this* side (which becomes our local). The service
    // method is named from the peer's perspective; the side effect on this
    // side is the swap.

    @Test
    fun `acceptHandshakeFromPeer flips OUTBOUND into INBOUND on the local side and swaps channel ids`() = runBlocking {
        val capturedDirection = slot<FederationSyncDirection>()
        val capturedLocal = slot<UUID>()
        val capturedRemote = slot<UUID>()
        coEvery {
            repository.upsertFederatedChannel(capture(capturedLocal), originPeerId, capture(capturedRemote), capture(capturedDirection))
        } returns Unit

        service.acceptHandshakeFromPeer(
            originPeerId = originPeerId,
            originChannelId = remoteChannelId, // peer's local
            remoteChannelId = localChannelId, // this side's local
            direction = FederationSyncDirection.OUTBOUND,
        )

        assertEquals(FederationSyncDirection.INBOUND, capturedDirection.captured)
        assertEquals(localChannelId, capturedLocal.captured)
        assertEquals(remoteChannelId, capturedRemote.captured)
    }

    @Test
    fun `acceptHandshakeFromPeer leaves BIDIRECTIONAL as-is and swaps the channel ids`() = runBlocking {
        val captured = slot<FederationSyncDirection>()
        val capturedLocal = slot<UUID>()
        val capturedRemote = slot<UUID>()
        coEvery {
            repository.upsertFederatedChannel(capture(capturedLocal), originPeerId, capture(capturedRemote), capture(captured))
        } returns Unit

        service.acceptHandshakeFromPeer(
            originPeerId = originPeerId,
            originChannelId = remoteChannelId,
            remoteChannelId = localChannelId,
            direction = FederationSyncDirection.BIDIRECTIONAL,
        )

        assertEquals(FederationSyncDirection.BIDIRECTIONAL, captured.captured)
        assertEquals(localChannelId, capturedLocal.captured)
        assertEquals(remoteChannelId, capturedRemote.captured)
    }

    @Test
    fun `acceptHandshakeFromPeer flips INBOUND into OUTBOUND on the local side`() = runBlocking {
        val captured = slot<FederationSyncDirection>()
        coEvery {
            repository.upsertFederatedChannel(any(), originPeerId, any(), capture(captured))
        } returns Unit

        service.acceptHandshakeFromPeer(
            originPeerId = originPeerId,
            originChannelId = remoteChannelId,
            remoteChannelId = localChannelId,
            direction = FederationSyncDirection.INBOUND,
        )

        assertEquals(FederationSyncDirection.OUTBOUND, captured.captured)
    }
}
