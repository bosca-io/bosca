package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A product's maker/brand. [extras] holds a serialized [ManufacturerExtras] (kept as `jsonb`/
 * `JsonElement` at the boundary; the marketplace later contributes vendor-linkage variants).
 */
@BatchKey("id")
@Serializable
data class Manufacturer(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val name: String,
    @property:DbMapper(JsonbMapper::class)
    val extras: ManufacturerExtras = EmptyManufacturerExtras,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
