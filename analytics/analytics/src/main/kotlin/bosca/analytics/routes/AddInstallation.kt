package bosca.analytics.routes

import bosca.analytics.installation.Installation
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.service.EventProcessingService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import java.time.Instant

@RouteController("/api/v1/installation", RouteMethod.POST)
open class AddInstallation(
    private val service: EventProcessingService
) : Route<Installation>() {

    public override fun serializer(): KSerializer<Installation> = Installation.serializer()

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Installation {
        val headers = call.request.headers
        val installationId = Installation.new()
        val now = Instant.now()
        service.queue(EventPipelineContext(headers), Events(
            context = null,
            events = listOf(Event(
                created = now.toEpochMilli(),
                createdMicros = 0,
                type = EventType.Installation,
                element = null,
                clientId = null
            )),
            sent = now.toEpochMilli(),
            sentMicros = 0,
            received = now.toEpochMilli(),
            receivedMicros = 0
        ))
        return installationId
    }
}

@RouteController("/api/v1/installation", RouteMethod.GET)
class AddInstallationGet(service: EventProcessingService) : AddInstallation(service)

@RouteController("/register", RouteMethod.POST)
class LegacyAddInstallation(service: EventProcessingService) : AddInstallation(service)

@RouteController("/register", RouteMethod.GET)
class LegacyAddInstallation2(service: EventProcessingService) : AddInstallation(service)
