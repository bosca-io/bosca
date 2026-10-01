package bosca.collaboration.routes

import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.di.ObjectProvider
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Receives federation handshake POSTs from peer Bosca instances. The peer
 * authenticates with `Authorization: Bearer <shared-secret>`, where the secret
 * is the one configured on this side under the peer's id (matching the
 * peer's `originPeerId` in the request body).
 *
 * Successful handshakes record an inbound federation link for the
 * (originChannelId, remoteChannelId) pair through
 * [FederationService.acceptHandshakeFromPeer]; the originator separately
 * records its own outbound link inside [FederationService.handshakeWithPeer].
 *
 * The endpoint deliberately does not surface details about whether the peer
 * id was known vs. the secret was wrong — it returns 401 in both cases — so
 * a misconfigured peer can't probe our peer registry.
 */
@RouteController(
    path = "/api/v1/federation/handshake",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.NONE,
)
class FederationHandshakeRoute(
    private val federationService: ObjectProvider<FederationService>,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val body = call.request.bodyText()
        val authorization = call.request.header("Authorization")
        val result = handle(body, authorization)
        call.respond(result.status, result.body)
    }

    /**
     * Pure orchestration — separated so unit tests can drive it without
     * the [ServerCall] / Netty plumbing. Returns the HTTP status the route
     * should respond with and an optional response body.
     */
    internal suspend fun handle(body: String, authorization: String?): HandshakeResult {
        val parsed = parseRequest(body) ?: return HandshakeResult(HttpStatusCode.BadRequest)
        val originPeerId = runCatching { UUID.parse(parsed.originPeerId) }.getOrNull()
            ?: return HandshakeResult(HttpStatusCode.BadRequest)
        val originChannelId = runCatching { UUID.parse(parsed.originChannelId) }.getOrNull()
            ?: return HandshakeResult(HttpStatusCode.BadRequest)
        val remoteChannelId = runCatching { UUID.parse(parsed.remoteChannelId) }.getOrNull()
            ?: return HandshakeResult(HttpStatusCode.BadRequest)
        val direction = runCatching { FederationSyncDirection.valueOf(parsed.syncDirection.uppercase()) }.getOrNull()
            ?: return HandshakeResult(HttpStatusCode.BadRequest)

        val service = federationService.get()
        // Authentication: the peer is identified by `originPeerId`, which on
        // this side is the local id of the FederationPeer row. Look up its
        // shared secret and compare against the bearer token in constant time.
        val expectedSecret = service.getSharedSecret(originPeerId)
        val presented = parseBearer(authorization)
        if (expectedSecret.isNullOrEmpty() || presented == null || !constantTimeEquals(expectedSecret, presented)) {
            return HandshakeResult(HttpStatusCode.Unauthorized)
        }

        runCatching {
            service.acceptHandshakeFromPeer(originPeerId, originChannelId, remoteChannelId, direction)
        }.onFailure {
            log.error("acceptHandshakeFromPeer failed for peer {}: {}", originPeerId, it.message, it)
            return HandshakeResult(HttpStatusCode.InternalServerError)
        }

        return HandshakeResult(HttpStatusCode.OK, """{"ok":true}""")
    }

    internal fun parseRequest(body: String): ParsedHandshake? {
        if (body.isBlank()) return null
        return runCatching {
            val element = Json.parseToJsonElement(body)
            val obj = element as? JsonObject ?: return@runCatching null
            ParsedHandshake(
                originPeerId = obj["originPeerId"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return@runCatching null,
                originChannelId = obj["originChannelId"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return@runCatching null,
                remoteChannelId = obj["remoteChannelId"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return@runCatching null,
                syncDirection = obj["syncDirection"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return@runCatching null,
            )
        }.getOrNull()
    }

    /**
     * Extracts the bearer credential from an `Authorization` header. Returns
     * null if the header is missing, empty, or doesn't use the bearer scheme.
     */
    internal fun parseBearer(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(' ', limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].equals("Bearer", ignoreCase = true)) return null
        val credential = parts[1].trim()
        return credential.ifBlank { null }
    }

    /**
     * Constant-time string comparison. Used to compare a presented bearer
     * credential against the configured shared secret without leaking
     * information about its length or content via early-return timing.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    internal data class ParsedHandshake(
        val originPeerId: String,
        val originChannelId: String,
        val remoteChannelId: String,
        val syncDirection: String,
    )

    internal data class HandshakeResult(val status: HttpStatusCode, val body: String = "")

    companion object {
        private val log = LoggerFactory.getLogger(FederationHandshakeRoute::class.java)
    }
}
