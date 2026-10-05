package bosca.collaboration.routes

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationSyncDirection
import bosca.collaboration.federation.LocalPeerIdProvider
import bosca.collaboration.repository.FederationRepository
import bosca.collaboration.service.FederationInboundListener
import bosca.collaboration.service.FederationServiceImpl
import bosca.configuration.model.Configuration as ConfigurationRow
import bosca.configuration.service.ConfigurationService
import bosca.di.ObjectProvider
import bosca.di.asProvider
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives [FederationHandshakeRoute.handle] directly so we can assert the
 * full request/response shape without a Netty test harness.
 *
 * The route delegates secret lookup back to [FederationServiceImpl.getSharedSecret];
 * we use a real FederationServiceImpl with mocked dependencies so the
 * secret-resolution path matches production exactly. The repository is
 * mocked so we can verify the local-side link is recorded after a
 * successful handshake.
 */
class FederationHandshakeRouteTest {

    private val repository = mockk<FederationRepository>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)
    private val profileService = mockk<ObjectProvider<ProfileService>>()
    private val inboundListener = mockk<ObjectProvider<FederationInboundListener>>()
    private val localPeerIdProvider = mockk<LocalPeerIdProvider>(relaxed = true)

    private val originPeerId = UUID.parse("11111111-2222-3333-4444-555555555555")
    private val originChannelId = UUID.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val remoteChannelId = UUID.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    private lateinit var service: FederationServiceImpl
    private lateinit var route: FederationHandshakeRoute

    @BeforeTest
    fun setup() {
        val listener = mockk<FederationInboundListener>(relaxed = true)
        coEvery { inboundListener.get() } returns listener
        service = FederationServiceImpl(
            repository = repository,
            configurationService = configurationService,
            profileService = profileService,
            inboundListener = inboundListener,
            localPeerIdProvider = localPeerIdProvider,
        )
        route = FederationHandshakeRoute(service.asProvider())
    }

    private fun installSecret(peerId: UUID, secret: String?) {
        if (secret == null) {
            coEvery { configurationService.getByKey("federation.peer.$peerId.secret") } returns null
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

    private fun makeBody(
        peerId: UUID = originPeerId,
        originChannel: UUID = originChannelId,
        remoteChannel: UUID = remoteChannelId,
        direction: String = "BIDIRECTIONAL",
    ) = """
        {
          "originPeerId": "$peerId",
          "originChannelId": "$originChannel",
          "remoteChannelId": "$remoteChannel",
          "syncDirection": "$direction"
        }
    """.trimIndent()

    // ---- happy path -----------------------------------------------------

    @Test
    fun `handle accepts a valid bearer-authenticated request and records the inverse link`() = runBlocking {
        installSecret(originPeerId, "shared-secret")

        val result = route.handle(makeBody(direction = "OUTBOUND"), authorization = "Bearer shared-secret")

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("""{"ok":true}""", result.body)
        // Direction was OUTBOUND (peer→us); local link should be INBOUND.
        coVerify(exactly = 1) {
            repository.upsertFederatedChannel(
                remoteChannelId,
                originPeerId,
                originChannelId,
                FederationSyncDirection.INBOUND,
            )
        }
    }

    @Test
    fun `handle accepts BIDIRECTIONAL handshakes and preserves the direction`() = runBlocking {
        installSecret(originPeerId, "shared-secret")

        val result = route.handle(makeBody(direction = "BIDIRECTIONAL"), authorization = "Bearer shared-secret")

        assertEquals(HttpStatusCode.OK, result.status)
        coVerify(exactly = 1) {
            repository.upsertFederatedChannel(any(), any(), any(), FederationSyncDirection.BIDIRECTIONAL)
        }
    }

    // ---- auth failures --------------------------------------------------

    @Test
    fun `handle rejects requests with no Authorization header`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle(makeBody(), authorization = null)
        assertEquals(HttpStatusCode.Unauthorized, result.status)
        coVerify(exactly = 0) { repository.upsertFederatedChannel(any(), any(), any(), any()) }
    }

    @Test
    fun `handle rejects requests with a non-Bearer Authorization scheme`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle(makeBody(), authorization = "Basic c2hhcmVkLXNlY3JldA==")
        assertEquals(HttpStatusCode.Unauthorized, result.status)
    }

    @Test
    fun `handle rejects requests with the wrong bearer token`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle(makeBody(), authorization = "Bearer wrong-secret")
        assertEquals(HttpStatusCode.Unauthorized, result.status)
        coVerify(exactly = 0) { repository.upsertFederatedChannel(any(), any(), any(), any()) }
    }

    @Test
    fun `handle rejects requests when no secret is configured for the originPeerId`() = runBlocking {
        installSecret(originPeerId, null)
        val result = route.handle(makeBody(), authorization = "Bearer anything")
        assertEquals(HttpStatusCode.Unauthorized, result.status)
    }

    @Test
    fun `handle rejects requests with an empty bearer credential`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle(makeBody(), authorization = "Bearer    ")
        assertEquals(HttpStatusCode.Unauthorized, result.status)
    }

    // ---- malformed payloads --------------------------------------------

    @Test
    fun `handle returns 400 on empty body`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle("", authorization = "Bearer shared-secret")
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `handle returns 400 on unparseable JSON`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle("{not-json", authorization = "Bearer shared-secret")
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `handle returns 400 when originPeerId is not a UUID`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val body = makeBody().replace(originPeerId.toString(), "not-a-uuid")
        val result = route.handle(body, authorization = "Bearer shared-secret")
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `handle returns 400 when syncDirection is unknown`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val body = makeBody(direction = "WRONG_WAY")
        val result = route.handle(body, authorization = "Bearer shared-secret")
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `handle returns 400 when required fields are missing`() = runBlocking {
        installSecret(originPeerId, "shared-secret")
        val result = route.handle(
            """{"originPeerId":"$originPeerId"}""",
            authorization = "Bearer shared-secret",
        )
        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    // ---- helper coverage ------------------------------------------------

    @Test
    fun `parseBearer extracts a valid bearer token`() {
        assertEquals("abc", route.parseBearer("Bearer abc"))
    }

    @Test
    fun `parseBearer is case-insensitive on the scheme name`() {
        assertEquals("abc", route.parseBearer("bearer abc"))
        assertEquals("abc", route.parseBearer("BEARER abc"))
    }

    @Test
    fun `parseBearer returns null for missing or non-bearer auth`() {
        assertNull(route.parseBearer(null))
        assertNull(route.parseBearer(""))
        assertNull(route.parseBearer("Basic abc"))
        assertNull(route.parseBearer("Bearer "))
        assertNull(route.parseBearer("Bearer"))
    }

    @Test
    fun `parseRequest returns null for an empty body`() {
        assertNull(route.parseRequest(""))
    }

    @Test
    fun `parseRequest returns null for unparseable JSON`() {
        assertNull(route.parseRequest("{not-json"))
    }

    @Test
    fun `parseRequest extracts every documented field`() {
        val parsed = route.parseRequest(makeBody())!!
        assertEquals(originPeerId.toString(), parsed.originPeerId)
        assertEquals(originChannelId.toString(), parsed.originChannelId)
        assertEquals(remoteChannelId.toString(), parsed.remoteChannelId)
        assertEquals("BIDIRECTIONAL", parsed.syncDirection)
    }
}
