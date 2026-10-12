package bosca.git.server

import bosca.analytics.server.AnalyticsServerClientModule
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
import bosca.git.dfs.DfsBlockCacheInitializer
import bosca.git.server.graphql.GitGraphQLService
import bosca.http.StatusPageModule
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.routes.configureGitCiRoutes
import bosca.routes.configureGitRoutes
import bosca.security.routes.SecurityRoutesModule
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/** Hosts the Git GraphQL API, authentication, smart transport and CI routes. */
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

    log.info("configuring git GraphQL API")
    val graphql =
        GitGraphQLService(
            environment.config
                .propertyOrNull("graphql.introspection.enabled")
                ?.getString()
                ?.toBoolean() ?: false
        )
    install(GraphQLModule(graphql))
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
