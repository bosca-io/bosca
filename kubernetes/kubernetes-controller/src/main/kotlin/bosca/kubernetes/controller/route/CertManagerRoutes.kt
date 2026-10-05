package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toCertificate
import bosca.kubernetes.controller.util.toIssuer
import bosca.kubernetes.model.CertificatesResponse
import bosca.kubernetes.model.IssuersResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

private val CERT_GROUP = "cert-manager.io"
private val CERT_VERSION = "v1"
private fun certContext(kind: String, namespaced: Boolean) = ResourceDefinitionContext.Builder()
    .withGroup(CERT_GROUP)
    .withVersion(CERT_VERSION)
    .withKind(kind)
    .withNamespaced(namespaced)
    .withPlural(when (kind) {
        "Certificate" -> "certificates"
        "Issuer" -> "issuers"
        "ClusterIssuer" -> "clusterissuers"
        else -> "${kind.lowercase()}s"
    })
    .build()

/**
 * Splits "cert-manager not installed" (404 — fine, debug) from "the
 * SA can't list the CRD" (403 — surface at WARN with the resource
 * details) from "real failure" (also WARN). Page renders empty
 * either way; this just makes the cause findable in logs.
 */
private fun logCertListFailure(kind: String, clusterId: UUID, t: Throwable) {
    val log = LoggerFactory.getLogger("bosca.kubernetes.controller.route.CertManager")
    when {
        t is KubernetesClientException && t.code == 404 ->
            log.debug("cert-manager {} CRD not installed on cluster {}", kind, clusterId)
        t is KubernetesClientException && t.code == 403 ->
            log.warn(
                "cert-manager {} list forbidden on cluster {} — kubeconfig SA needs `list` rights on cert-manager.io",
                kind, clusterId,
            )
        else ->
            log.warn("cert-manager {} list failed on cluster {}: {}", kind, clusterId, t.message)
    }
}

/**
 * cert-manager Certificate list. Returns an empty list (not an error)
 * when cert-manager is not installed — the CRD lookup fails gracefully
 * and the studio renders the "no certs yet" empty state.
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/certmanager/certificates",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class CertManagerCertificatesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<CertificatesResponse>() {

    override fun serializer(): KSerializer<CertificatesResponse> = CertificatesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): CertificatesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ctx = certContext("Certificate", namespaced = true)
                val ops = client.genericKubernetesResources(ctx)
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                CertificatesResponse(items = list.items.orEmpty().map { it.toCertificate() })
            } catch (e: Exception) {
                logCertListFailure("Certificate", clusterId, e)
                CertificatesResponse(items = emptyList())
            }
        }
    }
}

/**
 * cert-manager Issuer + ClusterIssuer list. Like the certificates
 * route, returns an empty list when cert-manager isn't installed.
 *
 * The `certs` count per issuer is computed via a single Certificate
 * scan that groups by `spec.issuerRef.name`. One extra list call, no
 * fan-out per issuer.
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/certmanager/issuers",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class CertManagerIssuersRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<IssuersResponse>() {

    override fun serializer(): KSerializer<IssuersResponse> = IssuersResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): IssuersResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            val certCounts = countCertsByIssuer(client, clusterId)

            val issuers = mutableListOf<GenericKubernetesResource>()
            runCatching {
                val ctx = certContext("Issuer", namespaced = true)
                val ops = client.genericKubernetesResources(ctx)
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                issuers.addAll(list.items.orEmpty())
            }.onFailure { logCertListFailure("Issuer", clusterId, it) }

            if (namespace == null) {
                runCatching {
                    val ctx = certContext("ClusterIssuer", namespaced = false)
                    val list = client.genericKubernetesResources(ctx).list()
                    issuers.addAll(list.items.orEmpty())
                }.onFailure { logCertListFailure("ClusterIssuer", clusterId, it) }
            }

            IssuersResponse(items = issuers.map { it.toIssuer(certCounts) })
        }
    }

    /**
     * Single cluster-wide Certificate scan, grouped by issuer name.
     * Returns both namespace-scoped keys (`namespace/name`) and bare
     * names (for ClusterIssuer matching) so [toIssuer] can resolve
     * either form with a single lookup.
     */
    @Suppress("UNCHECKED_CAST")
    private fun countCertsByIssuer(client: io.fabric8.kubernetes.client.KubernetesClient, clusterId: UUID): Map<String, Int> {
        return runCatching {
            val ctx = certContext("Certificate", namespaced = true)
            val all = client.genericKubernetesResources(ctx).inAnyNamespace().list().items.orEmpty()
            val counts = mutableMapOf<String, Int>()
            for (cert in all) {
                val spec = cert.additionalProperties["spec"] as? Map<String, Any?> ?: continue
                val issuerRef = spec["issuerRef"] as? Map<String, Any?> ?: continue
                val issuerName = issuerRef["name"] as? String ?: continue
                val issuerKind = (issuerRef["kind"] as? String) ?: "Issuer"
                val key = if (issuerKind == "ClusterIssuer") {
                    issuerName
                } else {
                    "${cert.metadata?.namespace.orEmpty()}/$issuerName"
                }
                counts[key] = (counts[key] ?: 0) + 1
            }
            counts
        }.getOrElse {
            logCertListFailure("Certificate (for issuer counts)", clusterId, it)
            emptyMap()
        }
    }
}
