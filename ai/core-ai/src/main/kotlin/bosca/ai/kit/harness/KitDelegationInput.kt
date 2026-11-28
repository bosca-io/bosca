package bosca.ai.kit.harness

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Immutable child brief and explicitly selected references; requestId is reused on redelivery. */
@Serializable
data class KitDelegationInput(
    @Contextual val requestId: UUID,
    val brief: String,
    val expectedResult: String,
    val profile: String,
    val context: List<KitContextSelection> = emptyList(),
    val artifacts: List<@Contextual UUID> = emptyList(),
    val scopes: List<String>? = null,
    val runtimeSeconds: Long = 300,
    @Contextual val deadline: OffsetDateTime? = null,
) {
    /** Bounds persisted task data before any conversation, credential or workload is created. */
    fun validate() {
        require(brief.isNotBlank() && brief.length <= 16000 && expectedResult.isNotBlank() && expectedResult.length <= 4000 &&
            profile.isNotBlank() && profile.length <= 256 && runtimeSeconds in 1..86400 && context.size + artifacts.size <= 16 &&
            artifacts.distinct().size == artifacts.size && context.distinct().size == context.size &&
            (scopes == null || scopes.isNotEmpty() && scopes.size <= 128 && scopes.distinct().size == scopes.size)) { "Invalid child task" }
        context.forEach(KitContextSelection::validate)
    }
}
