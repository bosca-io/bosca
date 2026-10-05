package bosca.server

import bosca.analytics.server.AnalyticsServerClientModule
import bosca.artifacts.docker.routes.DockerRoutesModule
import bosca.artifacts.helm.routes.HelmRoutesModule
import bosca.artifacts.maven.routes.MavenRoutesModule
import bosca.artifacts.ml.routes.MlRoutesModule
import bosca.artifacts.npm.routes.NpmRoutesModule
import bosca.artifacts.raw.routes.RawRoutesModule
import bosca.di.ArtifactsDockerProviderRegistrar
import bosca.di.ArtifactsHelmProviderRegistrar
import bosca.di.ArtifactsMavenProviderRegistrar
import bosca.di.ArtifactsMlProviderRegistrar
import bosca.di.ArtifactsNpmProviderRegistrar
import bosca.di.ArtifactsRawProviderRegistrar
import bosca.di.ArtifactsProviderRegistrar
import bosca.di.ArtifactsServerProviderRegistrar
import bosca.di.CoreArtifactsProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.security.routes.SecurityRoutesModule
import bosca.server.middleware.StatusPagesMiddleware
import bosca.server.netty.NettyServerEngine
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    NettyServerEngine.start("application.yaml", args) { module() }
}

suspend fun BoscaApplication.module() {
    install(InitializeModule(
        providers = arrayOf(
            ArtifactsServerProviderRegistrar(),
            ArtifactsProviderRegistrar(),
            ArtifactsNpmProviderRegistrar(),
            ArtifactsMavenProviderRegistrar(),
            ArtifactsHelmProviderRegistrar(),
            ArtifactsRawProviderRegistrar(),
            ArtifactsMlProviderRegistrar(),
            ArtifactsDockerProviderRegistrar(),
            CoreArtifactsProviderRegistrar(),
            CoreSecurityProviderRegistrar(),
            CoreStorageProviderRegistrar(),
            SecurityProviderRegistrar(),
            StorageProviderRegistrar(),
        )
    ))

    install(AnalyticsServerClientModule())

    val statusPages = StatusPagesMiddleware.Builder()
    statusPages.exception<SecurityException> { call, _ ->
        call.respond(HttpStatusCode.Forbidden, "")
    }
    statusPages.exception<IllegalArgumentException> { call, e ->
        call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad Request")
    }
    statusPages.exception<NoSuchElementException> { call, _ ->
        call.respond(HttpStatusCode.NotFound, "")
    }
    statusPages.defaultException { call, exception ->
        log.error("Failed to process request: ${call.request.path}", exception)
        call.respond(HttpStatusCode.InternalServerError, "Internal Server Error")
    }
    install(statusPages.build())

    install(SecurityRoutesModule())
    install(NpmRoutesModule())
    install(MavenRoutesModule())
    install(HelmRoutesModule())
    install(RawRoutesModule())
    install(MlRoutesModule())
    install(DockerRoutesModule())
    launch {
        PackageInstallations.install(this@module)
    }
}
