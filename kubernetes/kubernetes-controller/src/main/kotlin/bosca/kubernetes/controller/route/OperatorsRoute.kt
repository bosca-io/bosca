package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.groupCrdsToOperators
import bosca.kubernetes.model.OperatorsResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists installed operators by API group — one row per non-kubernetes
 * group, with the kinds it owns and the total live instance count.
 *
 * The instance count comes from listing one served version per CRD in
 * the group. That's an O(CRDs) fan-out — on a large cluster this can
 * be 50+ list calls, but the operator page is loaded rarely enough
 * that it's acceptable. A future optimisation reads counts from a
 * dedicated CR informer cache.
 *
 * Authorization: REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/operators",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class OperatorsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<OperatorsResponse>() {

    override fun serializer(): KSerializer<OperatorsResponse> = OperatorsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): OperatorsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val crds = client.apiextensions().v1().customResourceDefinitions().list().items
                val instancesByGroup = countInstancesByGroup(client, crds)
                OperatorsResponse(items = groupCrdsToOperators(crds, instancesByGroup))
            } catch (e: Exception) {
                log.warn("fabric8 operators list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    /**
     * For each CRD, picks one served version and lists instances.
     * Errors during a single list are logged but don't fail the whole
     * call — a malformed or RBAC-restricted CRD shouldn't blank the
     * operators page.
     */
    private fun countInstancesByGroup(
        client: KubernetesClient,
        crds: List<CustomResourceDefinition>,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (crd in crds) {
            val group = crd.spec?.group.orEmpty()
            if (group.isBlank()) continue
            val version = crd.spec?.versions.orEmpty().firstOrNull { it.served == true }?.name ?: continue
            val kind = crd.spec?.names?.kind ?: continue
            val namespaced = crd.spec?.scope.equals("Namespaced", ignoreCase = true) ?: false
            val context = ResourceDefinitionContext.Builder()
                .withGroup(group)
                .withVersion(version)
                .withKind(kind)
                .withNamespaced(namespaced)
                .build()
            try {
                val total = if (namespaced) {
                    client.genericKubernetesResources(context).inAnyNamespace().list().items.size
                } else {
                    client.genericKubernetesResources(context).list().items.size
                }
                counts[group] = (counts[group] ?: 0) + total
            } catch (e: Exception) {
                log.debug("count failed for {}/{}/{}: {}", group, version, kind, e.message)
            }
        }
        return counts
    }

    companion object {
        private val log = LoggerFactory.getLogger(OperatorsRoute::class.java)
    }
}
