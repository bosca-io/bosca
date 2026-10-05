package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.WorkloadKind
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Triggers a rolling restart of a workload by stamping the
 * `kubectl.kubernetes.io/restartedAt` annotation onto the pod
 * template. This is the same trick `kubectl rollout restart` uses —
 * mutating any field on the pod template forces the controller to
 * roll out a new ReplicaSet (Deployment), pod set (StatefulSet),
 * or DaemonSet revision.
 *
 * Supported kinds: `DEPLOYMENT`, `STATEFUL_SET`, `DAEMON_SET`. Others
 * are rejected with 400 — Jobs / CronJobs / ReplicaSets aren't
 * meaningfully "restartable" in this sense.
 *
 * URL: `POST /clusters/{id}/workloads/{kind}/{namespace}/{name}/restart`
 *
 * Authorization mirrors every other route: REQUIRED JWT plus an
 * independent admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/workloads/{kind}/{namespace}/{name}/restart",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class RestartWorkloadRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<Boolean>() {

    override fun serializer(): KSerializer<Boolean> = Boolean.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Boolean? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val kind = call.pathParameters["kind"]?.let {
            runCatching { WorkloadKind.valueOf(it) }.getOrNull()
        } ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val now = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            when (kind) {
                WorkloadKind.DEPLOYMENT -> {
                    client.apps().deployments().inNamespace(namespace).withName(name).edit { d: Deployment ->
                        d.spec.template = stampRestartAnnotation(d.spec.template, now)
                        d
                    }
                    true
                }
                WorkloadKind.STATEFUL_SET -> {
                    client.apps().statefulSets().inNamespace(namespace).withName(name).edit { s: StatefulSet ->
                        s.spec.template = stampRestartAnnotation(s.spec.template, now)
                        s
                    }
                    true
                }
                WorkloadKind.DAEMON_SET -> {
                    client.apps().daemonSets().inNamespace(namespace).withName(name).edit { d: DaemonSet ->
                        d.spec.template = stampRestartAnnotation(d.spec.template, now)
                        d
                    }
                    true
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest)
                    null
                }
            }
        }
    }

    /**
     * Sets `kubectl.kubernetes.io/restartedAt` on the pod template's
     * metadata. Preserves any existing annotations — overwriting
     * `template.metadata.annotations` outright would clobber sidecars
     * that depend on annotations like `linkerd.io/inject` or service
     * mesh config.
     */
    private fun stampRestartAnnotation(template: PodTemplateSpec, now: String): PodTemplateSpec {
        val metadata = template.metadata ?: io.fabric8.kubernetes.api.model.ObjectMeta()
        val annotations = metadata.annotations?.toMutableMap() ?: mutableMapOf()
        annotations["kubectl.kubernetes.io/restartedAt"] = now
        metadata.annotations = annotations
        template.metadata = metadata
        return template
    }
}
