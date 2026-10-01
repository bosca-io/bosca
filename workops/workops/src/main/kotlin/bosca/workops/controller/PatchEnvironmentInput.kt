package bosca.workops.controller

import bosca.serialization.UUID
import bosca.workops.model.environment.EnvironmentTargetType
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Partial GraphQL update; omitted values preserve the existing environment settings. */
@Serializable
data class PatchEnvironmentInput(
    val name: String,
    val description: String? = null,
    val displayOrder: Int? = null,
    val promotionSourceIds: List<@Contextual UUID> = emptyList(),
    val requiresApproval: Boolean? = null,
    val autoPromote: Boolean? = null,
    @Contextual val typeId: UUID,
    val targetType: EnvironmentTargetType? = null,
    val targetRef: String? = null,
    val ephemeral: Boolean? = null,
)
