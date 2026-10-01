package bosca.kubernetes.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.kubernetes.model.ApplyResult
import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.HelmInstallInput
import bosca.kubernetes.model.HelmUpgradeInput
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRepo
import bosca.kubernetes.model.K8sHpa
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.RegisterClusterInput
import bosca.kubernetes.model.UpdateClusterInput
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves every field under `mutation { kubernetes { ... } }`.
 *
 * Every mutation requires the `administrators` group — these operations
 * register or remove production cluster credentials, rotate kubeconfigs,
 * and (in subsequent phases) apply manifests / scale / delete /
 * exec / port-forward. There is no "editor can write" tier here. The
 * resolver verifies the group **before** any service call so an
 * unauthorized request never reaches the credential store.
 */
@TypeController(type = "KubernetesMutations")
class KubernetesMutationsController(
    private val clusters: ClusterService,
    private val controller: KubernetesControllerClient,
    private val groups: GroupEvaluator,
) : GraphQLController<KubernetesMutations> {

    @Field
    suspend fun registerCluster(
        authentication: AuthenticationContext,
        input: RegisterClusterInput,
    ): Cluster {
        groups.verifyHasAdminGroup(authentication)
        return clusters.register(input)
    }

    @Field
    suspend fun updateCluster(
        authentication: AuthenticationContext,
        id: UUID,
        input: UpdateClusterInput,
        expectedVersion: Long,
    ): Cluster {
        groups.verifyHasAdminGroup(authentication)
        return clusters.update(id, input, expectedVersion)
    }

    @Field
    suspend fun rotateKubeconfig(
        authentication: AuthenticationContext,
        id: UUID,
        kubeconfig: String,
    ): Cluster {
        groups.verifyHasAdminGroup(authentication)
        val updated = clusters.rotateKubeconfig(id, kubeconfig)
        // Drop the controller's cached fabric8 client so the next read
        // rebuilds with the new credential rather than continuing to
        // use a token revoked by the rotation.
        controller.invalidate(authentication, id)
        return updated
    }

    @Field
    suspend fun removeCluster(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        groups.verifyHasAdminGroup(authentication)
        val removed = clusters.remove(id)
        if (removed) {
            // Drop the cached client so any in-flight read against the
            // now-removed cluster fails fast on the next request,
            // instead of silently succeeding off a dangling client.
            controller.invalidate(authentication, id)
        }
        return removed
    }

    @Field
    suspend fun scaleWorkload(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
        replicas: Int,
    ): Workload {
        groups.verifyHasAdminGroup(authentication)
        require(replicas in 0..MAX_REPLICAS) {
            "replicas must be between 0 and $MAX_REPLICAS"
        }
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.scaleWorkload(authentication, cluster, namespace, kind, name, replicas)
    }

    @Field
    suspend fun restartWorkload(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        kind: WorkloadKind,
        name: String,
    ): Boolean {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.restartWorkload(authentication, cluster, namespace, kind, name)
    }

    @Field
    suspend fun updateHpaLimits(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
        minReplicas: Int,
        maxReplicas: Int,
    ): K8sHpa {
        groups.verifyHasAdminGroup(authentication)
        require(minReplicas >= 1) { "minReplicas must be >= 1" }
        require(maxReplicas in minReplicas..MAX_REPLICAS) {
            "maxReplicas must be between minReplicas and $MAX_REPLICAS"
        }
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.updateHpaLimits(authentication, cluster, namespace, name, minReplicas, maxReplicas)
    }

    @Field
    suspend fun deleteResource(
        authentication: AuthenticationContext,
        cluster: UUID,
        kind: String,
        name: String,
        namespace: String? = null,
        group: String? = null,
    ): Boolean {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.deleteResource(
            authentication = authentication,
            clusterId = cluster,
            kind = kind,
            name = name,
            namespace = namespace,
            group = group,
        ).deleted
    }

    @Field
    suspend fun createNamespace(
        authentication: AuthenticationContext,
        cluster: UUID,
        name: String,
        labels: JsonElement? = null,
    ): Namespace {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.createNamespace(authentication, cluster, name, labels)
    }

    @Field
    suspend fun applyManifest(
        authentication: AuthenticationContext,
        cluster: UUID,
        manifest: String,
        dryRun: Boolean? = null,
    ): ApplyResult {
        groups.verifyHasAdminGroup(authentication)
        require(manifest.length <= MAX_MANIFEST_BYTES) {
            "manifest must be <= $MAX_MANIFEST_BYTES bytes (got ${manifest.length})"
        }
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.applyManifest(authentication, cluster, manifest, dryRun ?: false)
    }

    @Field
    suspend fun helmRepoAdd(
        authentication: AuthenticationContext,
        name: String,
        url: String,
        username: String? = null,
        password: String? = null,
    ): K8sHelmRepo {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmRepoAdd(authentication, name, url, username, password)
    }

    @Field
    suspend fun helmRepoUpdate(authentication: AuthenticationContext): List<K8sHelmRepo> {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmRepoUpdate(authentication).items
    }

    @Field
    suspend fun helmRepoRemove(
        authentication: AuthenticationContext,
        name: String,
    ): Boolean {
        groups.verifyHasAdminGroup(authentication)
        return controller.helmRepoRemove(authentication, name)
    }

    @Field
    suspend fun helmInstall(
        authentication: AuthenticationContext,
        input: HelmInstallInput,
    ): K8sHelmRelease {
        groups.verifyHasAdminGroup(authentication)
        requireValuesSize(input.values)
        clusters.getById(input.cluster) ?: error("Cluster ${input.cluster} not found")
        return controller.helmInstall(
            authentication = authentication,
            clusterId = input.cluster,
            name = input.name,
            namespace = input.namespace,
            createNamespace = input.createNamespace,
            repo = input.repo,
            chart = input.chart,
            version = input.version,
            values = input.values,
            dryRun = input.dryRun,
        )
    }

    /**
     * Upgrade requires `repo` + `chart` from the originating release
     * since helm needs both to fetch the new chart tarball. They live
     * on `HelmUpgradeInput`; the studio's "Upgrade" flow pre-fills
     * them from the current release row.
     */
    @Field
    suspend fun helmUpgrade(
        authentication: AuthenticationContext,
        input: HelmUpgradeInput,
    ): K8sHelmRelease {
        groups.verifyHasAdminGroup(authentication)
        requireValuesSize(input.values)
        clusters.getById(input.cluster) ?: error("Cluster ${input.cluster} not found")
        return controller.helmUpgrade(
            authentication = authentication,
            clusterId = input.cluster,
            name = input.name,
            namespace = input.namespace,
            version = input.version,
            values = input.values,
            dryRun = input.dryRun,
            resetValues = input.resetValues,
            repo = input.repo,
            chart = input.chart,
        )
    }

    @Field
    suspend fun helmRollback(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
        toRevision: Int,
    ): K8sHelmRelease {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmRollback(authentication, cluster, namespace, name, toRevision)
    }

    @Field
    suspend fun helmUninstall(
        authentication: AuthenticationContext,
        cluster: UUID,
        namespace: String,
        name: String,
        keepHistory: Boolean? = null,
    ): Boolean {
        groups.verifyHasAdminGroup(authentication)
        clusters.getById(cluster) ?: error("Cluster $cluster not found")
        return controller.helmUninstall(authentication, cluster, namespace, name, keepHistory ?: false)
    }

    private fun requireValuesSize(values: String?) {
        if (values == null) return
        require(values.length <= MAX_VALUES_BYTES) {
            "values must be <= $MAX_VALUES_BYTES bytes (got ${values.length})"
        }
    }

    companion object {
        /**
         * Upper bound on a workload's replica count. The k8s API would
         * happily accept anything up to `Int.MAX_VALUE` and try to
         * schedule it — capping at 1000 prevents a typo or malicious
         * admin from DoS'ing the cluster scheduler.
         */
        const val MAX_REPLICAS: Int = 1000

        /**
         * Upper bound on `applyManifest` payload size. A 1 MiB cap
         * comfortably fits any realistic multi-doc YAML the studio
         * would compose while preventing a giant blob from wedging
         * fabric8 or the downstream controller.
         */
        const val MAX_MANIFEST_BYTES: Int = 1 * 1024 * 1024

        /**
         * Upper bound on helm install / upgrade `values` payload size.
         * Same threshold as [MAX_MANIFEST_BYTES] — large enough for any
         * realistic chart, small enough to keep the controller's helm
         * subprocess input bounded.
         */
        const val MAX_VALUES_BYTES: Int = 1 * 1024 * 1024
    }
}
