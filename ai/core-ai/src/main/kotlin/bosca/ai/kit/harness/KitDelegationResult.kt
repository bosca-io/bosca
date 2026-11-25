package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Bounded child output; terminal status separately records failure, cancellation or uncertainty. */
@Serializable
data class KitDelegationResult(val summary: String, val artifacts: List<KitDelegationArtifact> = emptyList()) {
    /** Keeps result submission within the task's typed content-reference contract. */
    fun validate() {
        require(summary.isNotBlank() && summary.length <= 16000 && artifacts.size <= 16 &&
            artifacts.map { it.metadataId }.distinct().size == artifacts.size && artifacts.all { it.label.length <= 256 }) { "Invalid child result" }
    }
}
