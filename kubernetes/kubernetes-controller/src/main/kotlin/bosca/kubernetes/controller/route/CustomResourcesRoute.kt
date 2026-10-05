package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toCustomResource
import bosca.kubernetes.model.CustomResourcesResponse
import bosca.kubernetes.model.K8sCustomResource
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists CR instances across every served CRD version.
 *
 * Optional `group` query parameter narrows to a single API group;
 * `namespace` narrows to a single namespace. Omitting both walks every
 * non-kubernetes-native CRD in the cluster — slow on large CRD
 * inventories, but the CRD browser page only opens on demand.
 *
 * Authorization: REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/customresources",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class CustomResourcesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<CustomResourcesResponse>() {

    override fun serializer(): KSerializer<CustomResourcesResponse> = CustomResourcesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): CustomResourcesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val params = call.request.queryParameters
        val groupFilter = params["group"]
        val namespaceFilter = params["namespace"]

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val crds = client.apiextensions().v1().customResourceDefinitions().list().items
                val collected = mutableListOf<K8sCustomResource>()

                for (crd in crds) {
                    val group = crd.spec?.group.orEmpty()
                    if (group.isBlank()) continue
                    if (groupFilter != null && groupFilter != group) continue

                    val kind = crd.spec?.names?.kind ?: continue
                    val namespaced = crd.spec?.scope.equals("Namespaced", ignoreCase = true) ?: false
                    val version = crd.spec?.versions.orEmpty().firstOrNull { it.served == true }?.name ?: continue
                    val context = ResourceDefinitionContext.Builder()
                        .withGroup(group)
                        .withVersion(version)
                        .withKind(kind)
                        .withNamespaced(namespaced)
                        .build()

                    try {
                        val instances = if (namespaceFilter != null && namespaced) {
                            client.genericKubernetesResources(context).inNamespace(namespaceFilter).list().items
                        } else if (namespaced) {
                            client.genericKubernetesResources(context).inAnyNamespace().list().items
                        } else {
                            client.genericKubernetesResources(context).list().items
                        }
                        instances.mapTo(collected) { it.toCustomResource(group, version) }
                    } catch (e: Exception) {
                        // Distinguish "this CRD is missing from the
                        // cluster" (404, fine — skip and move on)
                        // from "the kubeconfig SA can't list
                        // instances of this CRD" (403, surface at
                        // WARN with enough context to drive an RBAC
                        // change). Anything else also goes to WARN.
                        when {
                            e is KubernetesClientException && e.code == 404 ->
                                log.debug("CR list 404 for {}/{}/{} — not installed", group, version, kind)
                            e is KubernetesClientException && e.code == 403 ->
                                log.warn(
                                    "CR list forbidden for {}/{}/{} on cluster {} — kubeconfig SA needs `list` rights on this CRD",
                                    group, version, kind, clusterId,
                                )
                            else ->
                                log.warn("CR list failed for {}/{}/{} on cluster {}: {}", group, version, kind, clusterId, e.message)
                        }
                    }
                }

                CustomResourcesResponse(items = collected)
            } catch (e: Exception) {
                log.warn("fabric8 custom resources walk failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CustomResourcesRoute::class.java)
    }
}
