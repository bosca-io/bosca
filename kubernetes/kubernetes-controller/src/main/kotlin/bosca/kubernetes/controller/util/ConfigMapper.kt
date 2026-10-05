package bosca.kubernetes.controller.util

import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourceEntry
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Secret
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Mappers for ConfigMap / Secret into the wire [ConfigResource] /
 * [ConfigResourceEntry] shapes.
 *
 * The `keys` list combines `data` and `binaryData` entries — both are
 * user-visible keys in the kubectl sense. ConfigMap values are forwarded
 * as-is; Secret values are never emitted — see [SECRET_VALUE_REDACTED].
 */

/**
 * Fixed marker returned in place of any Kubernetes Secret value. Secret
 * cleartext (and its base64 wire form) never leaves the controller; callers
 * receive key names and this marker, never the decoded value or its length.
 */
const val SECRET_VALUE_REDACTED = "••••••"

fun ConfigMap.toConfigResource(): ConfigResource {
    val dataKeys = data?.keys ?: emptySet()
    val binaryKeys = binaryData?.keys ?: emptySet()
    val keys = (dataKeys + binaryKeys).toList().sorted()
    val rawSize = (data?.values?.sumOf { it.toByteArray(StandardCharsets.UTF_8).size } ?: 0) +
        (binaryData?.values?.sumOf { decodeBase64(it).size } ?: 0)
    return ConfigResource(
        id = metadata?.uid ?: "ConfigMap/${metadata?.namespace}/${metadata?.name}",
        kind = ConfigKind.CONFIG_MAP,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        keys = keys,
        size = humanSize(rawSize),
        age = formatAge(metadata?.creationTimestamp),
        secretType = null,
        managedBy = managedBy(metadata?.labels, metadata?.annotations),
    )
}

fun Secret.toConfigResource(): ConfigResource {
    // Secret.data values are already base64-encoded by the kubelet —
    // they're the wire form. We approximate "real size" by decoding
    // each value's length on the fly. Cheap; the values are typically
    // a few kilobytes total.
    val dataKeys = data?.keys ?: emptySet()
    val stringDataKeys = stringData?.keys ?: emptySet()
    val keys = (dataKeys + stringDataKeys).toList().sorted()
    val rawSize = (data?.values?.sumOf { decodeBase64(it).size } ?: 0) +
        (stringData?.values?.sumOf { it.toByteArray(StandardCharsets.UTF_8).size } ?: 0)
    return ConfigResource(
        id = metadata?.uid ?: "Secret/${metadata?.namespace}/${metadata?.name}",
        kind = ConfigKind.SECRET,
        name = metadata?.name.orEmpty(),
        namespace = metadata?.namespace.orEmpty(),
        keys = keys,
        size = humanSize(rawSize),
        age = formatAge(metadata?.creationTimestamp),
        secretType = type ?: "Opaque",
        managedBy = managedBy(metadata?.labels, metadata?.annotations),
    )
}

/**
 * Builds the entry list for a [ConfigMap] — values are forwarded as
 * UTF-8 strings; binary entries are emitted with a `base64:` prefix so
 * the caller can distinguish printable payloads from binary blobs.
 */
fun ConfigMap.toEntries(): List<ConfigResourceEntry> {
    val items = mutableListOf<ConfigResourceEntry>()
    data?.forEach { (k, v) -> items += ConfigResourceEntry(key = k, value = v) }
    binaryData?.forEach { (k, v) -> items += ConfigResourceEntry(key = k, value = "base64:$v") }
    return items.sortedBy { it.key }
}

/**
 * Builds the entry list for a [Secret]. Secret values are NEVER returned:
 * cleartext (and its base64 wire form) stays inside the controller. Callers
 * get the key names — metadata, not sensitive — each paired with a fixed
 * [SECRET_VALUE_REDACTED] marker, so a Secret's shape is visible with no path
 * to exfiltrate its contents. The marker is length-independent, so value
 * sizes don't leak either.
 */
fun Secret.toEntries(): List<ConfigResourceEntry> {
    val keys = ((data?.keys ?: emptySet()) + (stringData?.keys ?: emptySet())).toSortedSet()
    return keys.map { ConfigResourceEntry(key = it, value = SECRET_VALUE_REDACTED) }
}

/**
 * `managed-by` resolution: prefer the standard
 * `app.kubernetes.io/managed-by` label, then known operator-specific
 * annotations (`external-secrets.io`, `cert-manager.io`). Returns
 * null when no signal is present — the studio renders that as a
 * plain "—" instead of a misleading default like "kubectl".
 */
private fun managedBy(labels: Map<String, String>?, annotations: Map<String, String>?): String? {
    val labeled = labels?.get("app.kubernetes.io/managed-by")
    if (!labeled.isNullOrBlank()) return labeled
    val annKeys = annotations?.keys ?: return null
    return when {
        annKeys.any { it.startsWith("external-secrets.io/") } -> "external-secrets"
        annKeys.any { it.startsWith("cert-manager.io/") } -> "cert-manager"
        annKeys.any { it.startsWith("reloader.stakater.com/") } -> "stakater-reloader"
        else -> null
    }
}

private fun decodeBase64(b64: String): ByteArray = try {
    Base64.getDecoder().decode(b64)
} catch (_: Exception) {
    ByteArray(0)
}

private fun humanSize(bytes: Int): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) {
        val kb = bytes / 1024.0
        return if (kb >= 10) "${kb.toInt()}kB" else String.format("%.1fkB", kb)
    }
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 10) "${mb.toInt()}MB" else String.format("%.1fMB", mb)
}
