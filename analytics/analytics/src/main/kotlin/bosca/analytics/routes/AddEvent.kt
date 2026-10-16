package bosca.analytics.routes

import bosca.analytics.model.EventPipelineContext
import bosca.analytics.service.EventProcessingService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlin.time.Duration.Companion.seconds

@RouteController("/api/v1/events", RouteMethod.POST)
open class AddEvent(
    private val service: EventProcessingService
) : Route<Unit>() {

    public override fun serializer(): KSerializer<Unit>? = null

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val headers = call.request.headers
        val events = call.receive<bosca.analytics.model.Events>()
        val stored = try {
            withTimeoutOrNull(15.seconds) {
                service.queueAndWait(EventPipelineContext(headers), events)
                true
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The worker logs processing failures; clients retain the batch on a retryable response.
            false
        }
        if (!stored) {
            call.response.status(HttpStatusCode.ServiceUnavailable)
            return
        }
        if (call.request.path == "/events") {
            call.response.status(HttpStatusCode.OK)
        } else {
            call.response.status(HttpStatusCode.Accepted)
        }
    }
}

@RouteController("/events", RouteMethod.POST)
class LegacyAddEvent(service: EventProcessingService) : AddEvent(service)
