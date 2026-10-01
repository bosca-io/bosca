package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWire
import bosca.kubernetes.model.K8sRole
import bosca.kubernetes.model.K8sRoleBinding
import bosca.kubernetes.model.RoleBindingsResponse
import bosca.kubernetes.model.RolesResponse
import bosca.kubernetes.model.ServiceAccountsResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Lists Roles and/or ClusterRoles in `{id}`.
 *
 * Optional query parameter `kind` narrows to `Role` (namespaced) or
 * `ClusterRole` (cluster-scoped); omitting returns both unified, with
 * the `kind` field distinguishing rows. REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/roles",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class RolesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<RolesResponse>() {

    override fun serializer(): KSerializer<RolesResponse> = RolesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): RolesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val kindFilter = call.request.queryParameters["kind"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                // To count how many bindings reference each role we
                // need both kinds of binding regardless of which kind
                // of role we're listing — a Role can be referenced by
                // either a (namespaced) RoleBinding or a
                // ClusterRoleBinding, and likewise a ClusterRole by
                // either. Index keys are `(namespace, name)` for Role
                // bindings (namespace == null for ClusterRoles) and
                // (null, name) for ClusterRole bindings. We resolve a
                // role's binding count by checking *both* indices.
                val rbList = client.rbac().roleBindings().inAnyNamespace().list().items.orEmpty()
                val crbList = client.rbac().clusterRoleBindings().list().items.orEmpty()

                // For RoleBindings whose roleRef.kind == "Role" we
                // record (binding.namespace, role-name) because Role
                // ↔ RoleBinding is namespace-scoped. For RoleBindings
                // whose roleRef.kind == "ClusterRole" we record
                // (null, role-name) — same key shape as
                // ClusterRoleBindings — so a Role lookup never
                // matches a ClusterRole binding by accident.
                val byKey = HashMap<Pair<String?, String>, Int>()
                for (rb in rbList) {
                    val kind = rb.roleRef?.kind.orEmpty()
                    val name = rb.roleRef?.name.orEmpty()
                    val ns = if (kind == "Role") rb.metadata?.namespace else null
                    byKey.merge(ns to name, 1) { a, b -> a + b }
                }
                for (crb in crbList) {
                    val name = crb.roleRef?.name.orEmpty()
                    byKey.merge(null to name, 1) { a, b -> a + b }
                }

                val collected = mutableListOf<K8sRole>()
                if (kindFilter == null || kindFilter == "Role") {
                    client.rbac().roles().inAnyNamespace().list().items.mapTo(collected) { role ->
                        role.toWire(bindingCount = byKey[role.metadata?.namespace to role.metadata?.name.orEmpty()] ?: 0)
                    }
                }
                if (kindFilter == null || kindFilter == "ClusterRole") {
                    client.rbac().clusterRoles().list().items.mapTo(collected) { cr ->
                        cr.toWire(bindingCount = byKey[null to cr.metadata?.name.orEmpty()] ?: 0)
                    }
                }
                RolesResponse(items = collected)
            } catch (e: Exception) {
                log.warn("fabric8 roles list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(RolesRoute::class.java) }
}

/**
 * Lists RoleBindings and/or ClusterRoleBindings. Optional `kind`
 * narrows the same way [RolesRoute] does. REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/rolebindings",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class RoleBindingsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<RoleBindingsResponse>() {

    override fun serializer(): KSerializer<RoleBindingsResponse> = RoleBindingsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): RoleBindingsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val kindFilter = call.request.queryParameters["kind"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val collected = mutableListOf<K8sRoleBinding>()
                if (kindFilter == null || kindFilter == "RoleBinding") {
                    client.rbac().roleBindings().inAnyNamespace().list().items.mapTo(collected) { it.toWire() }
                }
                if (kindFilter == null || kindFilter == "ClusterRoleBinding") {
                    client.rbac().clusterRoleBindings().list().items.mapTo(collected) { it.toWire() }
                }
                RoleBindingsResponse(items = collected)
            } catch (e: Exception) {
                log.warn("fabric8 rolebindings list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(RoleBindingsRoute::class.java) }
}

/**
 * Lists ServiceAccounts, optionally filtered by `namespace`.
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/serviceaccounts",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ServiceAccountsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<ServiceAccountsResponse>() {

    override fun serializer(): KSerializer<ServiceAccountsResponse> = ServiceAccountsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): ServiceAccountsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.serviceAccounts()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()

                // Pods → SA: cluster-wide pod list, bucket by
                // `(namespace, spec.serviceAccountName)`. Pods that
                // don't set serviceAccountName get k8s's "default"
                // SA, which we honour so the `default` SA in each
                // namespace shows a real count.
                val podOps = client.pods()
                val pods = if (namespace != null) podOps.inNamespace(namespace).list() else podOps.inAnyNamespace().list()
                val podCounts = pods.items.orEmpty()
                    .groupingBy { (it.metadata?.namespace.orEmpty()) to (it.spec?.serviceAccountName ?: "default") }
                    .eachCount()

                // Bindings → SA: an SA appears in a binding via a
                // subject of `kind: ServiceAccount`. We count
                // distinct (binding-name) entries per SA so a single
                // binding granting access to one SA twice (rare but
                // valid) doesn't inflate the count.
                val rbList = client.rbac().roleBindings().inAnyNamespace().list().items.orEmpty()
                val crbList = client.rbac().clusterRoleBindings().list().items.orEmpty()
                val bindingCounts = HashMap<Pair<String, String>, Int>()
                for (rb in rbList) {
                    rb.subjects.orEmpty()
                        .filter { it.kind == "ServiceAccount" }
                        .forEach { s ->
                            val ns = s.namespace ?: rb.metadata?.namespace.orEmpty()
                            bindingCounts.merge(ns to s.name.orEmpty(), 1) { a, b -> a + b }
                        }
                }
                for (crb in crbList) {
                    crb.subjects.orEmpty()
                        .filter { it.kind == "ServiceAccount" }
                        .forEach { s ->
                            val ns = s.namespace.orEmpty()
                            bindingCounts.merge(ns to s.name.orEmpty(), 1) { a, b -> a + b }
                        }
                }

                ServiceAccountsResponse(items = list.items.map { sa ->
                    val key = (sa.metadata?.namespace.orEmpty()) to (sa.metadata?.name.orEmpty())
                    sa.toWire(
                        podCount = podCounts[key] ?: 0,
                        bindingCount = bindingCounts[key] ?: 0,
                    )
                })
            } catch (e: Exception) {
                log.warn("fabric8 serviceaccounts list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(ServiceAccountsRoute::class.java) }
}
