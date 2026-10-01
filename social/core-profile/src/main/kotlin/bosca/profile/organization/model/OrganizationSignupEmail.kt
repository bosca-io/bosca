package bosca.profile.organization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationSignupEmail(
    val email: String,
    @ColumnName("organization_id")
    val organizationId: UUID,
    @ColumnName("group_id")
    val groupId: UUID?,
    val created: OffsetDateTime,
    val expires: OffsetDateTime
)