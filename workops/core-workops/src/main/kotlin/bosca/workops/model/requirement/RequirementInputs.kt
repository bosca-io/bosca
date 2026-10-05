package bosca.workops.model.requirement

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CreateRequirementInput(
    @Contextual
    val metadataId: UUID? = null,
    val name: String? = null,
    val parentType: RequirementParent,
    @Contextual
    val parentId: UUID,
    @Contextual
    val workflowId: UUID? = null,
    @Contextual
    val priorityId: UUID? = null,
    @Contextual
    val assigneeProfileId: UUID? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class UpdateRequirementInput(
    @Contextual
    val priorityId: UUID? = null,
    @Contextual
    val assigneeProfileId: UUID? = null,
    val clearAssignee: Boolean = false,
    val sortOrder: Int? = null,
    val externalReferences: JsonElement? = null,
    val clearExternalReferences: Boolean = false,
    val expectedVersion: Long,
)
