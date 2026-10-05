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
 * A configured shipping-rate source. Behavior is a DI-registered ShippingProvider SPI selected by
 * [providerKey]; this row carries its settings. [key] is a business key unique across the
 * module. [configuration] is admin-permission-gated.
 */
@BatchKey("id")
@Serializable
data class ShippingProvider(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val name: String,
    val key: String,
    @ColumnName("provider_key")
    val providerKey: String,
    @property:DbMapper(JsonbMapper::class)
    val configuration: ProviderConfiguration = EmptyProviderConfiguration,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
