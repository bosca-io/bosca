package bosca.workops.model.audit

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SpecHistoryEntry(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("spec_id")
    @Contextual
    val specId: UUID,
    @ColumnName("changed_at")
    @Contextual
    val changedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("changed_by_principal_id")
    @Contextual
    val changedByPrincipalId: UUID,
    @ColumnName("changed_by_profile_id")
    @Contextual
    val changedByProfileId: UUID? = null,
    val changes: JsonElement,
)
