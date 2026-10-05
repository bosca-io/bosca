package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * Kind of config-style resource. Mirrors the GraphQL `ConfigKind`
 * enum so the studio's table can filter ConfigMaps and Secrets
 * separately or together.
 */
@Serializable
enum class ConfigKind { CONFIG_MAP, SECRET }

/**
 * A ConfigMap or Secret as surfaced by `kubernetes-controller`. Shape
 * mirrors the GraphQL `ConfigResource` type.
 *
 * Values are never returned in list responses — only the key set.
 * `configEntries` returns per-key values for ConfigMaps, but a Secret's
 * values are always masked: the controller never returns a Secret's
 * cleartext (nor its base64 wire form), on this endpoint or in YAML.
 */
@Serializable
data class ConfigResource(
    val id: String,
    val kind: ConfigKind,
    val name: String,
    val namespace: String,
    val keys: List<String>,
    val size: String,
    val age: String,
    val secretType: String? = null,
    val managedBy: String? = null,
)

/** Wire envelope for `GET /clusters/{id}/config`. */
@Serializable
data class ConfigResourcesResponse(val items: List<ConfigResource>)

/**
 * A single key/value entry inside a ConfigMap or Secret. For Secrets
 * the value has been base64-decoded server-side; binary payloads that
 * are not valid UTF-8 are returned as their base64 representation
 * prefixed with `base64:` so the studio can render them as-is rather
 * than corrupting bytes through a JSON string.
 */
@Serializable
data class ConfigResourceEntry(
    val key: String,
    val value: String,
)

/** Wire envelope for `GET /clusters/{id}/config/.../entries`. */
@Serializable
data class ConfigResourceEntriesResponse(val items: List<ConfigResourceEntry>)
