package bosca.atlas.server

import bosca.analytics.model.LiveSession
import bosca.routes.APIRoute
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.serialization.json.*

/** Publishes disposable heartbeats through Analytics and reports actual flow collection lifetimes. */
internal class AtlasReleaseLiveFixtureRoute(
    private val sessions: AtlasLiveSessionsFixture,
    private val groups: GroupEvaluator,
) : APIRoute<JsonObject>() {
    override fun serializer() = JsonObject.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject {
        groups.verifyHasAdminGroup(authenticationContext)
        val input = call.receive<JsonObject>()
        when (input.getValue("action").jsonPrimitive.content) {
            "publish", "complete" -> {
                val appId = input.getValue("appId").jsonPrimitive.content
                require(appId.startsWith("atlas-live-"))
                if (input.getValue("action").jsonPrimitive.content == "complete") sessions.complete(appId)
                sessions.publish(appId, LiveSession(
                    input.getValue("sessionId").jsonPrimitive.content,
                    input.getValue("lat").jsonPrimitive.double,
                    input.getValue("lon").jsonPrimitive.double,
                    appVersion = input["appVersion"]?.jsonPrimitive?.contentOrNull,
                ))
            }
            "stats" -> Unit
            else -> error("Unknown live-session fixture operation")
        }
        return sessions.snapshot()
    }
}
