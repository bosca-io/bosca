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
 * The commerce identity of a thing that can be sold. Name/description/merchandising content live on
 * the linked content [metadataId] document at the **pinned** [metadataVersion]; this row carries only
 * commerce facts. The pin advances when a new document version is published through the content
 * workflow (there is no ecom publish mutation). Pricing lives on CatalogProduct entries.
 * [configuration] is a serialized [ProductConfiguration] (sealed).
 */
@BatchKey("id")
@Serializable
data class Product(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("manufacturer_id")
    val manufacturerId: UUID,
    @ColumnName("manufacturer_sku")
    val manufacturerSku: String,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    @ColumnName("metadata_version")
    val metadataVersion: Int = 1,
    val type: ProductType,
    @property:DbMapper(JsonbMapper::class)
    val configuration: ProductConfiguration = StandardProductConfiguration,
    /** Shipping weight; intrinsic to the product (used by shipping-rate calculation). */
    val weight: Double = 1.0,
    /** Shipping dimensions (unitless; 0 = unknown). Used by the packer to box for density. */
    val width: Double = 0.0,
    val height: Double = 0.0,
    val length: Double = 0.0,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
