package bosca.analytics.routes

import bosca.analytics.service.EventProcessingService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/events/flush", RouteMethod.GET)
open class Flush(
    private val service: EventProcessingService,
    private val groupEvaluator: GroupEvaluator
) : Route<Unit>() {

    public override fun serializer(): KSerializer<Unit>? = null

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        service.flush()
    }
}
