package bosca.artifacts.maven.routes

import bosca.routes.configureArtifactsMavenRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class MavenRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsMavenRoutes()
    }
}
