package bosca.workops.model.spec

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@BatchKey("id")
@Serializable
data class SpecTaskGeneration(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("spec_id")
    @Contextual
    val specId: UUID,
    @ColumnName("metadata_version")
    val metadataVersion: Int,
    val source: GenerationSource,
    @ColumnName("agent_session_id")
    @Contextual
    val agentSessionId: UUID? = null,
    @ColumnName("generated_task_ids")
    val generatedTaskIds: List<@Contextual UUID> = emptyList(),
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("created_by_principal_id")
    @Contextual
    val createdByPrincipalId: UUID,
)

@Serializable
data class CreateSpecTaskGenerationInput(
    @Contextual
    val specId: UUID,
    val metadataVersion: Int,
    val source: GenerationSource,
    @Contextual
    val agentSessionId: UUID? = null,
    val generatedTaskIds: List<@Contextual UUID> = emptyList(),
)
