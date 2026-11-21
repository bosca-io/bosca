package bosca.atlas.server

import bosca.analytics.model.Device
import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.service.ErrorGroupService
import bosca.counter.Counter
import bosca.routes.APIRoute
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.serialization.json.*
import java.time.Instant

/** Seeds only disposable metrics through the real counter and error-group persistence boundaries. */
internal class AtlasReleaseHealthFixtureRoute(
    private val counter: Counter,
    private val errors: ErrorGroupService,
    private val groups: GroupEvaluator,
) : APIRoute<JsonObject>() {
    override fun serializer() = JsonObject.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonObject {
        groups.verifyHasAdminGroup(authenticationContext)
        val input = call.receive<JsonObject>()
        val app = input.getValue("app").jsonPrimitive.content
        val service = input.getValue("service").jsonPrimitive.content
        require(app.startsWith("atlas-health-") && service.startsWith("atlas-health-"))
        val now = Instant.now()
        val sessionBucket = now.epochSecond / 900
        val httpBucket = now.epochSecond / 60 - 1
        counter.increment("sessions.$app.$sessionBucket", 7)
        counter.increment("sessions.$app.${sessionBucket - 1}", 3)
        for ((status, value) in listOf("2xx" to 100L, "4xx" to 5L, "5xx" to 5L)) {
            counter.increment("http.$service.$status.$httpBucket", value)
        }
        val timestamp = now.toEpochMilli()
        errors.recordBatch(Events(
            context = EventContext(appId = app, appVersion = "1.0.0", sessionId = "atlas-health-fixture",
                device = Device("atlas-health-fixture", "Fixture", "Browser", "web", "en", "Fixture", "UTC", "test", "1")),
            events = (0 until 70).map { index -> Event(
                created = timestamp - (69 - index) * 1000,
                type = EventType.Error,
                error = ErrorInfo(message = "Release health fixture $index", type = "FixtureError", fatal = index < 60,
                    fingerprint = "atlas-health-fixture-$index"),
            ) },
            sent = timestamp, sentMicros = timestamp * 1000,
        ))
        return buildJsonObject {
            put("sessionTime", Instant.ofEpochSecond(sessionBucket * 900).toString())
            put("httpTime", Instant.ofEpochSecond(httpBucket * 60).toString())
            put("latestError", Instant.ofEpochMilli(timestamp).toString())
        }
    }
}
