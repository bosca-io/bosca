package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.formatAge
import bosca.kubernetes.model.CreateNamespaceRequest
import bosca.kubernetes.model.Namespace
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates a new namespace.
 *
 * URL: `POST /clusters/{id}/namespaces`
 * Body: `{ "name": "<string>", "labels": { ... } | null }`
 *
 * The `labels` map is forwarded as `metadata.labels` on the new
 * Namespace; non-string values are coerced to strings (kubernetes
 * label values must be strings). Returns the newly-created Namespace
 * mapped to our wire shape — `workloads/pods/services` start at zero
 * because the informer cache hasn't observed the new namespace yet.
 *
 * Authorization mirrors every other route: REQUIRED JWT + admin
 * re-check.
 */
@RouteController(
    path = "/clusters/{id}/namespaces",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class CreateNamespaceRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<Namespace>() {

    override fun serializer(): KSerializer<Namespace> = Namespace.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Namespace? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val body = call.receive<CreateNamespaceRequest>()
        if (body.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }

        val labelMap: Map<String, String> = (body.labels as? JsonObject)
            ?.mapValues { (_, v) -> coerceLabel(v) }
            ?: emptyMap()

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            val ns = NamespaceBuilder()
                .withNewMetadata()
                .withName(body.name)
                .also { meta -> if (labelMap.isNotEmpty()) meta.addToLabels(labelMap) }
                .endMetadata()
                .build()
            val created = client.namespaces().resource(ns).create()
            Namespace(
                name = created.metadata?.name.orEmpty(),
                status = created.status?.phase ?: "Active",
                workloads = 0,
                pods = 0,
                services = 0,
                age = formatAge(created.metadata?.creationTimestamp),
            )
        }
    }

    /**
     * Kubernetes label values must be strings. JSON booleans / numbers
     * coerce via `toString()`; null becomes an empty string so the
     * label is present but un-set rather than dropped on the floor.
     */
    private fun coerceLabel(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonPrimitive -> if (value.isString) value.content else value.toString()
        else -> value.toString()
    }
}
