package bosca.git.server

import bosca.analytics.server.AnalyticsServerClientModule
import bosca.analytics.server.analyticsContext
import bosca.bml.server.LocalGraphQLBackend
import bosca.configuration.GraphQLModule
import bosca.di.ContentProviderRegistrar
import bosca.di.CoreContentProviderRegistrar
import bosca.di.CoreGitCiProviderRegistrar
import bosca.di.CoreGitProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.CoreProfileProviderRegistrar
import bosca.di.CoreSchedulerProviderRegistrar
import bosca.di.CoreSearchProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.GitCiProviderRegistrar
import bosca.di.GitJobsProviderRegistrar
import bosca.di.GitProviderRegistrar
import bosca.di.GitServerProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.SchedulerProviderRegistrar
import bosca.di.SearchProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.SlugProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.provide
import bosca.git.dfs.DfsBlockCacheInitializer
import bosca.git.server.graphql.GitGraphQLService
import bosca.git.ui.installGitUi
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.graphql.GraphQLWebSocketProtocolHandler
import bosca.http.StatusPageModule
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.routes.configureGitCiRoutes
import bosca.routes.configureGitRoutes
import bosca.security.routes.BoscaAuthMiddleware
import bosca.security.routes.SecurityRoutesModule
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine
import bosca.server.routing.AuthConfig
import kotlinx.serialization.json.Json

/** Hosts Git transports, CI routes and the embedded BML UI on one HTTP engine. */
fun main(args: Array<String>) {
    DfsBlockCacheInitializer.initialize()
    NettyServerEngine.start("application.yaml", args) { module() }
}

suspend fun BoscaApplication.module() {
    val registrars = gitServerProviderRegistrars()

    log.info("enabled providers: ${registrars.joinToString { it.javaClass.simpleName }}")
    install(InitializeModule(providers = registrars))

    log.info("configuring analytics client")
    install(AnalyticsServerClientModule())

    log.info("configuring auth module")
    install(SecurityRoutesModule())

    log.info("installing packages")
    PackageInstallations.install(this)

    log.info("configuring git UI")
    val graphql =
        GitGraphQLService(
            environment.config
                .propertyOrNull("graphql.introspection.enabled")
                ?.getString()
                ?.toBoolean() ?: false
        )
    install(GraphQLModule(graphql))
    installGitSubscriptions(graphql)
    val authentication = provide<BoscaAuthMiddleware>()
    val providers = provide<AuthenticationProviders>()
    val backend =
        LocalGraphQLBackend(
            graphql,
            requestMetadata = { call, installation, session ->
                call.analyticsContext =
                    call.analyticsContext.copy(
                        appId =
                            System.getenv("BML_APP_ID")?.takeIf(String::isNotBlank)
                                ?: call.analyticsContext.appId,
                        appVersion =
                            System.getenv("APP_VERSION")?.takeIf(String::isNotBlank)
                                ?: call.analyticsContext.appVersion,
                        installationId = installation,
                        sessionId = session,
                    )
            },
        ) { call, token ->
            if (token != null) {
                authentication.authenticateBearerToken(call, token.removePrefix("Bearer "))
            } else {
                authentication.authenticate(
                    call,
                    AuthConfig(providers.providers.toList(), optional = true),
                )
                !call.response.isCommitted
            }
        }
    // UI routes must precede the transport's generic /{owner}/{repo} browser redirect.
    installGitUi(
        this,
        backend,
        environment.config.propertyOrNull("git.ui.authCookiePrefix")?.getString()?.takeIf {
            it.isNotBlank()
        },
    )
    router.get("/welcome") { call.respondRedirect("/repositories") }

    log.info("configuring git routes")
    configureGitRoutes()
    configureGitCiRoutes()

    log.info("configuring status page")
    install(StatusPageModule())
}

internal fun gitServerProviderRegistrars() =
    arrayOf(
        CoreProfileProviderRegistrar(),
        ProfileProviderRegistrar(),
        CoreSecurityProviderRegistrar(),
        SecurityProviderRegistrar(),
        CoreStorageProviderRegistrar(),
        StorageProviderRegistrar(),
        CoreContentProviderRegistrar(),
        ContentProviderRegistrar(),
        CoreSearchProviderRegistrar(),
        SearchProviderRegistrar(),
        SlugProviderRegistrar(),
        CoreSchedulerProviderRegistrar(),
        SchedulerProviderRegistrar(),
        CorePipelinesProviderRegistrar(),
        PipelinesProviderRegistrar(),
        GitServerProviderRegistrar(),
        CoreGitProviderRegistrar(),
        CoreGitCiProviderRegistrar(),
        GitProviderRegistrar(),
        GitCiProviderRegistrar(),
        GitJobsProviderRegistrar(),
    )

/** Runs CI subscriptions on this process using the same engine and token validation as GraphQL. */
internal suspend fun BoscaApplication.installGitSubscriptions(service: GitGraphQLService) {
    val providers = provide<AuthenticationProviders>()
    val authenticator = provide<GraphQLConnectionInitAuthenticator>()
    val json = provide<Json>()
    router.authenticate(*providers.providers, optional = true) {
        webSocket("/_git/subscriptions", "graphql-transport-ws") {
            GraphQLWebSocketProtocolHandler.handle(
                this,
                json,
                service::subscribe,
                onConnectionInit = authenticator::authenticate,
            )
        }
    }
}
