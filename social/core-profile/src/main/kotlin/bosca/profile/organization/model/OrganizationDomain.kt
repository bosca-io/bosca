package bosca.profile.organization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationDomain(
    @Contextual
    @ColumnName("organization_id")
    val organizationId: UUID,
    val domain: String,
    @ColumnName("auto_join")
    val autoJoin: Boolean,
    @ColumnName("group_id")
    val groupId: UUID? = null
)