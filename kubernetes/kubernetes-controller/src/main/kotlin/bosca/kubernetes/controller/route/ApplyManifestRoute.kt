package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.ApplyFailure
import bosca.kubernetes.model.ApplyManifestRequest
import bosca.kubernetes.model.ApplyResult
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Applies a YAML manifest (single doc or `---`-separated multi-doc)
 * via fabric8's server-side apply.
 *
 * URL: `POST /clusters/{id}/apply`
 * Body: `{ "manifest": "<yaml>", "dryRun": <bool> }`
 *
 * Each document is dispatched independently: a failure on document
 * three does not abort documents one and two — the caller gets a
 * detailed [ApplyResult] listing which resources succeeded and which
 * failed, so the studio's apply modal can render a precise outcome
 * report. `dryRun=true` switches every dispatch to server-side dry-
 * run mode, so the API server validates and returns the would-be
 * state without persisting it.
 *
 * The `fieldManager` we register is `bosca-server` — that's the
 * identity that owns fields written through this route, which makes
 * `kubectl get -o yaml --show-managed-fields` show "this came from
 * Bosca". Future drift detection will use the same field-manager id.
 *
 * Authorization mirrors every other route: REQUIRED JWT + admin
 * re-check.
 */
@RouteController(
    path = "/clusters/{id}/apply",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class ApplyManifestRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<ApplyResult>() {

    override fun serializer(): KSerializer<ApplyResult> = ApplyResult.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): ApplyResult? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val body = call.receive<ApplyManifestRequest>()
        if (body.manifest.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            val items = runCatching { parseManifest(client, body.manifest) }.getOrElse { e ->
                log.warn("manifest parse failed for cluster {}: {}", clusterId, e.message)
                return@withContext ApplyResult(
                    succeeded = false,
                    applied = emptyList(),
                    failed = listOf(ApplyFailure(resource = "<parse>", error = e.message ?: "parse failed")),
                    dryRun = null,
                )
            }
            applyAll(client, items, body.dryRun)
        }
    }

    /**
     * Loads a YAML document stream via fabric8's parser, which already
     * understands `---` separation and the full Kubernetes model
     * registry. `Serialization.unmarshal` is the fallback for single
     * docs where `client.load(...)` may interpret a single object as
     * a list of one.
     */
    private fun parseManifest(client: KubernetesClient, yaml: String): List<HasMetadata> {
        val stream = ByteArrayInputStream(yaml.toByteArray(StandardCharsets.UTF_8))
        val loaded = client.load(stream).items()
        if (loaded.isNotEmpty()) return loaded
        // Fall back to single-object unmarshal for inputs that the
        // loader rejected (rare — usually only happens for non-Kubernetes
        // YAML or malformed multi-doc separators).
        val single = Serialization.unmarshal<HasMetadata>(yaml)
        return if (single != null) listOf(single) else emptyList()
    }

    private fun applyAll(
        client: KubernetesClient,
        items: List<HasMetadata>,
        dryRun: Boolean,
    ): ApplyResult {
        val applied = mutableListOf<String>()
        val failed = mutableListOf<ApplyFailure>()
        for (item in items) {
            val ref = resourceRef(item)
            try {
                val handle = client.resource(item)
                if (dryRun) {
                    handle.dryRun().fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()
                } else {
                    handle.fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()
                }
                applied += ref
            } catch (e: Exception) {
                log.warn("apply failed for {}: {}", ref, e.message)
                failed += ApplyFailure(resource = ref, error = e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return ApplyResult(
            succeeded = failed.isEmpty(),
            applied = applied,
            failed = failed,
            dryRun = if (dryRun) buildDryRunSummary(applied, failed) else null,
        )
    }

    /**
     * Formats `Kind/namespace/name` (or `Kind/name` for cluster-scoped
     * resources). Matches the format used by the studio's apply modal
     * and by `kubectl`-style output.
     */
    private fun resourceRef(item: HasMetadata): String {
        val kind = item.kind.orEmpty()
        val ns = item.metadata?.namespace
        val name = item.metadata?.name.orEmpty()
        return if (ns.isNullOrBlank()) "$kind/$name" else "$kind/$ns/$name"
    }

    /**
     * Builds a human-readable summary for the `dryRun` field. The
     * GraphQL schema documents this as "server-side dry-run output";
     * a strict-fidelity YAML render is a follow-up — for now this
     * tells the user how many docs would be applied and lists them.
     */
    private fun buildDryRunSummary(applied: List<String>, failed: List<ApplyFailure>): String {
        val lines = mutableListOf<String>()
        if (applied.isNotEmpty()) {
            lines += "# would apply ${applied.size} resource${if (applied.size == 1) "" else "s"}:"
            applied.forEach { lines += "#   $it" }
        }
        if (failed.isNotEmpty()) {
            lines += "# ${failed.size} resource${if (failed.size == 1) "" else "s"} would fail:"
            failed.forEach { lines += "#   ${it.resource}: ${it.error}" }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApplyManifestRoute::class.java)
        private const val FIELD_MANAGER = "bosca-server"
    }
}
