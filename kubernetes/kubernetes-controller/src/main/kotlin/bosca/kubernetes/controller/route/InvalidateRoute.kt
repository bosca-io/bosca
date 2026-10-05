package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Signal from `bosca-server` that the cached fabric8 client for
 * `{id}` is no longer valid and should be dropped.
 *
 * Called by `bosca-server` immediately after `rotateKubeconfig` or
 * `removeCluster` mutations so the next read rebuilds the client
 * against the new credential (or fails fast on a removed cluster).
 *
 * Authorization mirrors every other route: REQUIRED JWT plus an
 * independent `administrators` re-check. This isn't a public
 * pop-the-cache primitive — only admins can trigger an invalidation,
 * and they reach it through bosca-server's mutation flow.
 *
 * Idempotent: invalidating an absent id is a no-op.
 */
@RouteController(
    path = "/clusters/{id}/invalidate",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class InvalidateRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<InvalidateResponse>() {

    override fun serializer(): KSerializer<InvalidateResponse> = InvalidateResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): InvalidateResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        pool.invalidate(clusterId)
        return InvalidateResponse(ok = true)
    }
}

@Serializable
data class InvalidateResponse(val ok: Boolean)
