package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourceEntry
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.K8sEvent
import bosca.kubernetes.model.K8sIngress
import bosca.kubernetes.model.K8sNetworkPolicy
import bosca.kubernetes.model.K8sNode
import bosca.kubernetes.model.K8sPvc
import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.K8sCertificate
import bosca.kubernetes.model.K8sCnpgCluster
import bosca.kubernetes.model.K8sCnpgClusterDetail
import bosca.kubernetes.model.K8sCustomResource
import bosca.kubernetes.model.K8sGateway
import bosca.kubernetes.model.K8sGatewayClass
import bosca.kubernetes.model.K8sHpa
import bosca.kubernetes.model.K8sPdb
import bosca.kubernetes.model.K8sHelmChart
import bosca.kubernetes.model.K8sHelmChartVersion
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.K8sHelmRevision
import bosca.kubernetes.model.K8sHttpRoute
import bosca.kubernetes.model.K8sIssuer
import bosca.kubernetes.model.K8sOperator
import bosca.kubernetes.model.K8sService
import bosca.kubernetes.model.K8sServiceAccount
import bosca.kubernetes.model.K8sStorageClass
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.PodsResponse
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

/**
 * Resolves every field under `query { kubernetes { ... } }`.
 *
 * **Authorization model.** Cluster-level operations expose secrets,
 * pod logs, workload definitions, and node identity — exactly the
 * surface an attacker who breaches Bosca would want. Every resolver
 * in this controller therefore requires the `administrators` group:
 * we deliberately do not split into editor-readable vs admin-only
 * tiers. Finer-grained per-cluster or per-namespace roles are a
 * future expansion (kubernetes-admin role tied to a specific cluster
 * id), but the v1 floor is administrators-only.
 *
 * The controller also re-verifies admin group on every call before
 * issuing the JWT to the downstream `kubernetes-controller`. That
 * binary independently re-validates the JWT and the admin claim, so
 * a bug here does not give callers a free pass.
 */
@TypeController(type = "KubernetesQueries")
class KubernetesQueriesController(
    private val clusters: ClusterService,
    private val controller: KubernetesControllerClient,
    private val groups: GroupEvaluator,
) : GraphQLController<KubernetesQueries> {

    @Field
    suspend fun clusters(authentication: AuthenticationContext): List<Cluster> {
        groups.verifyHasAdminGroup(authentication)
        return clusters.list()
    }

    @Field
    suspend fun cluster(authentication: AuthenticationContext, id: UUID): Cluster? {
        groups.verifyHasAdminGroup(authentication)
        return clusters.getById(id)
    }

    @Field
    suspend fun namespaces(authentication: AuthenticationContext, cluster: UUID): List<Namespace> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.namespaces(authentication, cluster).items
    }

    @Field
    suspend fun workloads(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        kind: WorkloadKind? = null,
    ): List<Workload> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.workloads(authentication, cluster, namespace, kind).items
    }

    @Field
    suspend fun pods(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        workloadId: String? = null,
        search: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): PodsResponse {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.pods(authentication, cluster, namespace, workloadId, search, limit, offset)
    }

    @Field
    suspend fun nodes(authentication: AuthenticationContext, cluster: UUID): List<K8sNode> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.nodes(authentication, cluster).items
    }

    @Field
    suspend fun events(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        level: EventLevel? = null,
        limit: Int? = null,
    ): List<K8sEvent> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.events(authentication, cluster, namespace, level, limit).items
    }

    @Field
    suspend fun configResources(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
        kind: ConfigKind? = null,
    ): List<ConfigResource> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.configResources(authentication, cluster, namespace, kind).items
    }

    @Field
    suspend fun configEntries(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        kind: ConfigKind,
        name: String,
    ): List<ConfigResourceEntry> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        if (kind == ConfigKind.SECRET) {
            // Secret values are masked by the controller and never returned;
            // we still audit access to a Secret's keys so ops can correlate
            // against the JWT principal in their audit pipeline.
            audit.warn(
                "secret entries accessed (values masked): principal={} cluster={} namespace={} name={}",
                authentication.principal()?.id, cluster, namespace, name,
            )
        }
        return controller.configEntries(authentication, cluster, kind, namespace, name).items
    }

    @Field
    suspend fun services(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sService> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.services(authentication, cluster, namespace).items
    }

    @Field
    suspend fun ingresses(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sIngress> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.ingresses(authentication, cluster, namespace).items
    }

    @Field
    suspend fun networkPolicies(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sNetworkPolicy> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.networkPolicies(authentication, cluster, namespace).items
    }

    @Field
    suspend fun storageClasses(
        authentication: AuthenticationContext,
        cluster: UUID,
    ): List<K8sStorageClass> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.storageClasses(authentication, cluster).items
    }

    @Field
    suspend fun pvcs(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sPvc> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.pvcs(authentication, cluster, namespace).items
    }

    @Field
    suspend fun horizontalPodAutoscalers(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sHpa> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.horizontalPodAutoscalers(authentication, cluster, namespace).items
    }

    @Field
    suspend fun podDisruptionBudgets(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sPdb> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.podDisruptionBudgets(authentication, cluster, namespace).items
    }

    @Field
    suspend fun roles(
        authentication: AuthenticationContext,
        cluster: UUID,
        kind: String? = null,
    ): List<K8sRole> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.roles(authentication, cluster, kind).items
    }

    @Field
    suspend fun roleBindings(
        authentication: AuthenticationContext,
        cluster: UUID,
        kind: String? = null,
    ): List<K8sRoleBinding> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.roleBindings(authentication, cluster, kind).items
    }

    @Field
    suspend fun serviceAccounts(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sServiceAccount> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.serviceAccounts(authentication, cluster, namespace).items
    }

    @Field
    suspend fun yaml(
        authentication: AuthenticationContext,
        cluster: UUID,
        kind: String,
        name: String,
        namespace: String? = null,
        group: String? = null,
    ): String {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        if (kind.equals("Secret", ignoreCase = true)) {
            // The controller redacts a Secret's data before serializing its
            // YAML, so no values are returned here either; we still audit the
            // access. Match `Secret` case-insensitively because `kind` is a
            // free-form string from the resolver (not the typed ConfigKind).
            audit.warn(
                "secret manifest accessed (values masked): principal={} cluster={} namespace={} name={} via=yaml",
                authentication.principal()?.id, cluster, namespace, name,
            )
        }
        return controller.yaml(
            authentication = authentication,
            clusterId = cluster,
            kind = kind,
            name = name,
            namespace = namespace,
            group = group,
        ).yaml
    }

    @Field
    suspend fun operators(
        authentication: AuthenticationContext,
        cluster: UUID,
    ): List<K8sOperator> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.operators(authentication, cluster).items
    }

    @Field
    suspend fun customResources(
        authentication: AuthenticationContext,
        cluster: UUID,
        group: String? = null,
        namespace: String? = null,
    ): List<K8sCustomResource> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.customResources(authentication, cluster, group, namespace).items
    }

    @Field
    suspend fun gatewayClasses(
        authentication: AuthenticationContext,
        cluster: UUID,
    ): List<K8sGatewayClass> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.gatewayClasses(authentication, cluster).items
    }

    @Field
    suspend fun gateways(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sGateway> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.gateways(authentication, cluster, namespace).items
    }

    @Field
    suspend fun httpRoutes(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sHttpRoute> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.httpRoutes(authentication, cluster, namespace).items
    }

    @Field
    suspend fun certManagerCertificates(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sCertificate> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.certManagerCertificates(authentication, cluster, namespace).items
    }

    @Field
    suspend fun certManagerIssuers(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sIssuer> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.certManagerIssuers(authentication, cluster, namespace).items
    }

    @Field
    suspend fun cnpgClusters(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sCnpgCluster> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.cnpgClusters(authentication, cluster, namespace).items
    }

    @Field
    suspend fun cnpgCluster(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
    ): K8sCnpgClusterDetail? {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.cnpgClusterDetail(authentication, cluster, namespace, name).detail
    }

    @Field
    suspend fun helmRepos(authentication: AuthenticationContext): List<K8sHelmRepo> {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmRepos(authentication).items
    }

    @Field
    suspend fun helmCharts(
        authentication: AuthenticationContext,
        repo: String? = null,
        search: String? = null,
    ): List<K8sHelmChart> {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmCharts(authentication, repo, search).items
    }

    @Field
    suspend fun helmChartVersions(
        authentication: AuthenticationContext,
        repo: String,
        chart: String,
    ): List<K8sHelmChartVersion> {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmChartVersions(authentication, repo, chart).items
    }

    @Field
    suspend fun helmChartValues(
        authentication: AuthenticationContext,
        repo: String,
        chart: String,
        version: String,
    ): K8sHelmChartValues {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmChartValues(authentication, repo, chart, version)
    }

    @Field
    suspend fun helmReleases(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String? = null,
    ): List<K8sHelmRelease> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmReleases(authentication, cluster, namespace).items
    }

    @Field
    suspend fun helmReleaseHistory(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
    ): List<K8sHelmRevision> {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmReleaseHistory(authentication, cluster, namespace, name).items
    }

    @Field
    suspend fun helmReleaseValues(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
        revision: Int? = null,
    ): String {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmReleaseValues(authentication, cluster, namespace, name, revision)
    }

    @Field
    suspend fun helmReleaseManifest(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
        revision: Int? = null,
    ): String {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmReleaseManifest(authentication, cluster, namespace, name, revision)
    }

    companion object {
        /**
         * Dedicated audit channel — kept under its own logger name so
         * ops can route `bosca.kubernetes.audit` separately from the
         * controller's normal application logs (e.g. ship to the
         * security SIEM, retain longer, alert on volume spikes).
         */
        private val audit = LoggerFactory.getLogger("bosca.kubernetes.audit")
    }
}
