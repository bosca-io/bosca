package bosca.kubernetes.controller

import bosca.analytics.server.AnalyticsServerClientModule
import bosca.di.CoreKubernetesProviderRegistrar
import bosca.di.CoreSecurityProviderRegistrar
import bosca.di.KubernetesControllerProviderRegistrar
import bosca.di.SecurityProviderRegistrar
import bosca.di.provideBlockingNoSuspend
import bosca.http.StatusPageModule
import bosca.initialization.InitializeModule
import bosca.installer.model.PackageInstallations
import bosca.kubernetes.controller.cluster.ClusterHealthProbe
import bosca.kubernetes.controller.jobs.KubernetesJobController
import bosca.routes.configureKubernetesControllerRoutes
import bosca.security.routes.AuthenticationModule
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/**
 * Bosca Kubernetes Controller — a standalone native binary that owns
 * connections to one or more Kubernetes clusters and exposes them to
 * the rest of the Bosca platform over HTTP (sync) and WebSocket
 * (streams).
 *
 * Uses the same [NettyServerEngine] and [BoscaApplication] framework
 * as the main Bosca server. Compiled via GraalVM native-image; the
 * JVM fallback is `./gradlew :kubernetes-controller:run`.
 *
 * Phase 1 loads only the shared kubernetes model types ([CoreKubernetesProviderRegistrar])
 * and the controller's own routes / services ([KubernetesControllerProviderRegistrar]).
 * The full runtime kubernetes module is intentionally NOT loaded — its
 * GraphQL resolvers and JDBC repositories belong to bosca-server, not
 * to this binary.
 */
fun main(args: Array<String>) {
    NettyServerEngine.start("application.yaml", args) { module() }
}

suspend fun BoscaApplication.module() {
    val registrars = listOf(
        CoreSecurityProviderRegistrar(),
        SecurityProviderRegistrar(),
        CoreKubernetesProviderRegistrar(),
        KubernetesControllerProviderRegistrar(),
    )

    log.info("enabled providers: ${registrars.joinToString { it.javaClass.simpleName }}")
    install(InitializeModule(providers = registrars.toTypedArray()))

    log.info("configuring analytics client")
    install(AnalyticsServerClientModule())

    log.info("configuring auth module")
    install(AuthenticationModule())

    log.info("installing packages")
    PackageInstallations.install(this)

    log.info("configuring status page")
    install(StatusPageModule())

    log.info("configuring controller routes")
    configureKubernetesControllerRoutes()

    // Kick off the cluster health probe once the DI container is wired
    // and routes are mounted. The probe runs as a daemon coroutine and
    // updates `kubernetes.cluster.last_seen_at` / `health` on each tick;
    // see [ClusterHealthProbe] for the failure-counter behaviour.
    log.info("starting cluster health probe")
    provideBlockingNoSuspend<ClusterHealthProbe>().start()

    log.info("starting Kubernetes Job controller")
    provideBlockingNoSuspend<KubernetesJobController>().start()
}
