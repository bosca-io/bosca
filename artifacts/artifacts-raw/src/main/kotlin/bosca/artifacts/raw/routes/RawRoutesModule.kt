package bosca.artifacts.raw.routes

import bosca.routes.configureArtifactsRawRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class RawRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsRawRoutes()
    }
}
