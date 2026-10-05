package bosca.git.server

import bosca.di.CoreContentProviderRegistrar
import bosca.di.CoreGitCiProviderRegistrar
import bosca.di.CoreGitProviderRegistrar
import bosca.di.CoreProfileProviderRegistrar
import bosca.di.CorePipelinesProviderRegistrar
import bosca.di.CoreSearchProviderRegistrar
import bosca.di.CoreSchedulerProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.CoreStorageProviderRegistrar
import bosca.di.GitCiProviderRegistrar
import bosca.di.GitJobsProviderRegistrar
import bosca.di.GitProviderRegistrar
import bosca.di.GitServerProviderRegistrar
import bosca.di.ProfileProviderRegistrar
import bosca.di.PipelinesProviderRegistrar
import bosca.di.SearchProviderRegistrar
import bosca.di.SchedulerProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.SlugProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.analytics.server.AnalyticsServerClientModule
import bosca.git.dfs.DfsBlockCacheInitializer
import bosca.http.StatusPageModule
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.routes.configureGitCiRoutes
import bosca.routes.configureGitRoutes
import bosca.security.routes.AuthenticationModule
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/**
 * Bosca Git Server — a standalone native binary that serves git repositories
 * over HTTPS smart transport.
 *
 * Uses the same [NettyServerEngine] and [BoscaApplication] framework as the
 * main Bosca server but bundles only the modules needed for git operations:
 * core (DB, routing, caching), core-security (auth), core-git (models),
 * git (services, repositories, DFS), and git-jobs (GC, webhooks).
 *
 * Compiled via GraalVM native-image for sub-100ms startup and ~50MB idle RSS.
 * A JVM fallback is available via `./gradlew :backend:servers:git-server:run`.
 */
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
    install(AuthenticationModule())

    log.info("installing packages")
    PackageInstallations.install(this)

    log.info("configuring git routes")
    configureGitRoutes()
    configureGitCiRoutes()

    log.info("configuring status page")
    install(StatusPageModule())
}

internal fun gitServerProviderRegistrars() = arrayOf(
    CoreProfileProviderRegistrar(),
    ProfileProviderRegistrar(),
    CoreSecurityProviderRegistrar(),
    SecurityProviderRegistrar(),
    CoreStorageProviderRegistrar(),
    StorageProviderRegistrar(),
    CoreContentProviderRegistrar(),
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
