package bosca.artifacts.npm.routes

import bosca.routes.configureArtifactsNpmRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class NpmRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsNpmRoutes()
    }
}
