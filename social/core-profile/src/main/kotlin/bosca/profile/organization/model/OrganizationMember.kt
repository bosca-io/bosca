package bosca.profile.organization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationMember(
    @Contextual
    @ColumnName("organization_id")
    val organizationId: UUID,
    @Contextual
    @ColumnName("principal_id")
    val principalId: UUID
)