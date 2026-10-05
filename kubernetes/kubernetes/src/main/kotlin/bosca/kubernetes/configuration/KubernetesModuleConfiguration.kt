package bosca.kubernetes.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.kubernetes.migration.KubernetesMigration
import bosca.kubernetes.jobs.KubernetesJobQueueNames
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory

/**
 * Wires the kubernetes module's runtime dependencies into Bosca's DI container.
 *
 * This module is loaded only into `bosca-server` and owns the
 * cluster-registration surface (clusters table, GraphQL controllers,
 * mutation flow) plus the HTTP client that talks to the standalone
 * `kubernetes-controller`. The encrypted-kubeconfig read path lives
 * in `core-kubernetes` so both binaries can decrypt credentials —
 * `bosca-server` writes them via [ClusterService.register], and
 * `kubernetes-controller` reads them to build fabric8 clients.
 *
 * Service implementations are registered through generated providers; this
 * class retains only explicitly named infrastructure and client providers.
 */
@Providers
class KubernetesModuleConfiguration {

    @Provider(name = "kubernetes-migrations")
    fun migration(): Migration = KubernetesMigration()

    @Provider(singleton = true, name = KubernetesJobQueueNames.jobQueue)
    fun kubernetesJobsQueue(factory: JobQueueFactory): JobQueue =
        factory.create(KubernetesJobQueueNames.queue)

    @Provider(singleton = true)
    fun kubernetesControllerClient(securityService: SecurityService): KubernetesControllerClient {
        val baseUrl = System.getenv("KUBERNETES_CONTROLLER_URL")
            ?: KubernetesControllerClient.DEFAULT_BASE_URL
        return KubernetesControllerClient(baseUrl = baseUrl, securityService = securityService)
    }
}
