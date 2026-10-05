package bosca.artifacts.ml.routes

import bosca.routes.configureArtifactsMlRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class MlRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsMlRoutes()
    }
}
