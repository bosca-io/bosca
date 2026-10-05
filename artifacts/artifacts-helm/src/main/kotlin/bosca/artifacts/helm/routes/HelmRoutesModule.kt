package bosca.artifacts.helm.routes

import bosca.routes.configureArtifactsHelmRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class HelmRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsHelmRoutes()
    }
}
