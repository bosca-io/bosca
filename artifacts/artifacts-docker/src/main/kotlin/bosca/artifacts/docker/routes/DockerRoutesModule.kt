package bosca.artifacts.docker.routes

import bosca.routes.configureArtifactsDockerRoutes
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

class DockerRoutesModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        application.configureArtifactsDockerRoutes()
    }
}
