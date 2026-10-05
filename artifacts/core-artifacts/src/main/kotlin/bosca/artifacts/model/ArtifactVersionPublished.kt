package bosca.artifacts.model

import kotlinx.serialization.Serializable

/**
 * PubSub announcement that an artifact version (or docker tag) landed in the registry.
 * Published on [CHANNEL] by the registry write paths — version creation for
 * maven/npm/helm/raw/ml, tag upsert for docker — so consumers gating on artifact existence
 * (the CI requirement checker) can re-evaluate within seconds instead of waiting for a sweep.
 *
 * The payload is deliberately plain strings: cross-service subscribers deserialize their own
 * local copy of this shape (the platform's loose-coupling convention for wire events), so the
 * fields are the contract — additive changes only. [type] carries [ArtifactType.value]
 * (`docker`, `maven`, …).
 */
@Serializable
data class ArtifactVersionPublished(
    val namespace: String,
    val repository: String,
    val type: String,
    val version: String,
) {
    companion object {
        const val CHANNEL = "bosca.artifacts.version.published"
    }
}
