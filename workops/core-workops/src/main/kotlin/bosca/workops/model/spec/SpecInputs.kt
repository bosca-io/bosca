package bosca.workops.model.spec

import bosca.workops.model.WorkOpsValidationException
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CreateSpecInput(
    @Contextual
    val metadataId: UUID? = null,
    val name: String? = null,
    @Contextual
    val programId: UUID? = null,
    @Contextual
    val projectId: UUID? = null,
    @Contextual
    val parentSpecId: UUID? = null,
    val sortOrder: Int = 0,
    @Contextual
    val workflowId: UUID? = null,
    @Contextual
    val gitRepositoryId: UUID? = null,
    val gitPath: String? = null,
) {
    init {
        if (gitRepositoryId != null && gitPath == null) {
            throw WorkOpsValidationException("gitPath", "required when gitRepositoryId is set")
        }
    }
}

@Serializable
data class UpdateSpecInput(
    @Contextual
    val programId: UUID? = null,
    val clearProgramId: Boolean = false,
    @Contextual
    val projectId: UUID? = null,
    val clearProjectId: Boolean = false,
    @Contextual
    val ownerProfileId: UUID? = null,
    @Contextual
    val parentSpecId: UUID? = null,
    val clearParentSpecId: Boolean = false,
    val sortOrder: Int? = null,
    @Contextual
    val gitRepositoryId: UUID? = null,
    val clearGitRepositoryId: Boolean = false,
    val gitPath: String? = null,
    val clearGitPath: Boolean = false,
    val externalReferences: JsonElement? = null,
    val clearExternalReferences: Boolean = false,
    val expectedVersion: Long,
)
