package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A seller/tenant. Identity (name, branding, membership) lives on the linked organization profile in
 * the profiles domain ([organizationId] / [profileId], created together via
 * `OrganizationService.add`); this row carries only the commerce linkage. Everything else in the
 * module hangs off a company.
 */
@BatchKey("id")
@Serializable
data class Company(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("organization_id")
    val organizationId: UUID,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    /** The unit this company's product/container dimensions are stored in (defaults inches/pounds). */
    @ColumnName("length_unit")
    val lengthUnit: LengthUnit = LengthUnit.INCHES,
    @ColumnName("weight_unit")
    val weightUnit: WeightUnit = WeightUnit.POUNDS,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
