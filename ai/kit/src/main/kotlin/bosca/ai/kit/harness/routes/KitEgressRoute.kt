package bosca.ai.kit.harness.routes

import bosca.ai.kit.harness.KitEgressDestinations
import bosca.ai.kit.harness.KitHarnessService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

/**
 * Egress proxy lookup for one runtime generation. The generation's egress token, not a Bosca credential,
 * authenticates the request; unknown sessions, mismatched tokens and retired generations are all not found.
 */
@RouteController("/api/v1/kit/egress", method = RouteMethod.GET)
open class KitEgressRoute(private val harness: KitHarnessService) : Route<KitEgressDestinations>() {

    override fun serializer(): KSerializer<KitEgressDestinations> = KitEgressDestinations.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): KitEgressDestinations? {
        val session = call.request.headers[SESSION_HEADER]?.let { value ->
            try { UUID.parse(value) } catch (_: IllegalArgumentException) { null }
        } ?: return null
        val token = call.request.headers[TOKEN_HEADER]?.takeIf { it.isNotBlank() } ?: return null
        return harness.egress(session, token)
    }

    private companion object {
        const val SESSION_HEADER = "X-Bosca-Kit-Session"
        const val TOKEN_HEADER = "X-Bosca-Kit-Egress"
    }
}
