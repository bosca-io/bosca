package bosca.kubernetes.controller.util

import bosca.kubernetes.model.K8sCertificate
import bosca.kubernetes.model.K8sIssuer
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Mappers for cert-manager CRDs into the wire shape. We can't use a
 * typed model — cert-manager doesn't ship fabric8 bindings — so the
 * mappers reach into [GenericKubernetesResource.additionalProperties]
 * with explicit casts. The casts are defensive: anything missing or
 * shaped wrong yields a sensible default rather than a thrown
 * exception. cert-manager CRDs are stable enough that mis-shapes
 * indicate a different CRD version, not a transient deserialisation
 * blip.
 */

private fun Map<*, *>?.string(key: String): String? = (this?.get(key) as? String)?.takeIf { it.isNotBlank() }

@Suppress("UNCHECKED_CAST")
fun GenericKubernetesResource.toCertificate(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): K8sCertificate {
    val spec = additionalProperties["spec"] as? Map<String, Any?>
    val status = additionalProperties["status"] as? Map<String, Any?>

    val dns = (spec?.get("dnsNames") as? List<*>).orEmpty().mapNotNull { it as? String }
    val issuerRef = spec?.get("issuerRef") as? Map<String, Any?>
    val issuerKind = issuerRef.string("kind") ?: "Issuer"
    val issuerName = issuerRef.string("name").orEmpty()
    val issuer = if (issuerName.isBlank()) "" else "$issuerKind/$issuerName"
    val secretName = spec.string("secretName").orEmpty()

    val conditions = (status?.get("conditions") as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
    val ready = conditions.firstOrNull { it["type"] == "Ready" }
    val issuing = conditions.firstOrNull { it["type"] == "Issuing" }
    val readyStatus = ready.string("status")
    val statusLabel = when {
        readyStatus == "True" -> "Ready"
        issuing?.string("status") == "True" -> "Renewing"
        readyStatus == "False" -> "Failed"
        else -> "Pending"
    }

    val readyTransitionAt = parseOptional(ready.string("lastTransitionTime"))
    val readySince = if (readyTransitionAt != null) formatRelativeTime(ready.string("lastTransitionTime"), now) else "-"
    val notAfter = parseOptional(status.string("notAfter"))
    val renewalTime = parseOptional(status.string("renewalTime"))
    val expiresInDays = notAfter?.let { Duration.between(now.toInstant(), it.toInstant()).toDays().toInt() } ?: 0
    val renewsInDays = renewalTime?.let { Duration.between(now.toInstant(), it.toInstant()).toDays().toInt() } ?: -1

    val errorMsg = if (readyStatus == "False") {
        ready.string("message") ?: issuing.string("message")
    } else null

    return K8sCertificate(
        id = metadata?.uid ?: "Certificate/${metadata?.namespace}/${metadata?.name}",
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        dns = dns,
        issuer = issuer,
        status = statusLabel,
        readySince = readySince,
        expiresInDays = expiresInDays,
        renewsInDays = renewsInDays,
        secretName = secretName,
        error = errorMsg,
    )
}

@Suppress("UNCHECKED_CAST")
fun GenericKubernetesResource.toIssuer(certCountsByName: Map<String, Int>): K8sIssuer {
    val spec = additionalProperties["spec"] as? Map<String, Any?>
    val status = additionalProperties["status"] as? Map<String, Any?>

    // Type is the first populated discriminator key under spec.
    val (type, server) = deriveIssuerType(spec)

    val conditions = (status?.get("conditions") as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
    val ready = conditions.firstOrNull { it["type"] == "Ready" }
    val statusLabel = when (ready.string("status")) {
        "True" -> "Ready"
        "False" -> "Failed"
        else -> "Pending"
    }

    val key = "${metadata?.namespace.orEmpty()}/${metadata?.name.orEmpty()}"
    val countByKey = certCountsByName[key]
        ?: certCountsByName[metadata?.name.orEmpty()]   // fallback for ClusterIssuer (no namespace)
        ?: 0

    return K8sIssuer(
        id = metadata?.uid ?: "$kind/${metadata?.namespace}/${metadata?.name}",
        kind = kind ?: "Issuer",
        name = metadata?.name.orEmpty(),
        type = type,
        server = server,
        status = statusLabel,
        age = formatAge(metadata?.creationTimestamp),
        certs = countByKey,
        namespace = metadata?.namespace,
    )
}

@Suppress("UNCHECKED_CAST")
private fun deriveIssuerType(spec: Map<String, Any?>?): Pair<String, String> {
    spec ?: return "Unknown" to ""
    val acme = spec["acme"] as? Map<String, Any?>
    if (acme != null) return "ACME" to (acme["server"] as? String).orEmpty()
    if (spec.containsKey("ca")) return "CA" to ""
    val vault = spec["vault"] as? Map<String, Any?>
    if (vault != null) return "Vault" to (vault["server"] as? String).orEmpty()
    if (spec.containsKey("selfSigned")) return "SelfSigned" to ""
    return "Other" to ""
}

private fun parseOptional(s: String?): OffsetDateTime? {
    if (s.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
