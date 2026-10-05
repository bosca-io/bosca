package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * Wire envelope for the generic `yaml()` resolver — a single-field
 * wrapper around the rendered YAML string. Modelling it as a typed
 * response makes future enrichments (managed-fields stripping, last-
 * applied annotation handling) source-compatible.
 */
@Serializable
data class YamlResponse(
    val yaml: String,
)
