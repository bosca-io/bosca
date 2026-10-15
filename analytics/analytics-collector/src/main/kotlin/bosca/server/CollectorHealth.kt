package bosca.server

import bosca.analytics.repository.nats.NatsEventRepository
import bosca.analytics.service.EventProcessingHealth
import bosca.di.provide
import bosca.initialization.ReadyResponse

/** Installs collector probes; liveness reads only local state and never waits on NATS. */
fun BoscaApplication.configureCollectorHealth() {
    val usesNats = environment.config.propertyOrNull("eventRepository.type")?.getString() == "nats"
    routing {
        get("/api/v1/collector/ready") {
            val health = provide<EventProcessingHealth>()
            if (usesNats) NatsEventRepository.checkReadiness(provide(), health)
            val ready = health.isReady
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadyResponse(if (ready) "ready" else "not ready"),
                ReadyResponse.serializer(),
            )
        }
        get("/api/v1/collector/live") {
            val live = provide<EventProcessingHealth>().isLive
            call.respond(
                if (live) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                ReadyResponse(if (live) "live" else "processing unavailable for 60 seconds"),
                ReadyResponse.serializer(),
            )
        }
    }
}
