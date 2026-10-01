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
 * A sellable entry: a product placed in a catalog at a [price], within an availability window
 * ([starts]..[ends]). This is what carts reference and what storefronts list. [type] is denormalized
 * from the product for fast type-filtered listing. For SUBSCRIPTION entries, [price] is the
 * signup/first-period charge (renewals use the plan price snapshotted into the subscription).
 */
@BatchKey("id")
@Serializable
data class CatalogProduct(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("catalog_id")
    val catalogId: UUID,
    @Contextual
    @ColumnName("product_id")
    val productId: UUID,
    val type: ProductType,
    val price: Money,
    val taxable: Boolean = true,
    @Contextual
    val starts: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val ends: OffsetDateTime = OffsetDateTime.now(),
    /** Promotion codes pre-associated with this entry. */
    val promotions: List<String> = emptyList(),
    /** Per-catalog-entry extension data (e.g. quantity limits). */
    @property:DbMapper(JsonbMapper::class)
    val extras: CatalogProductExtras = EmptyCatalogProductExtras,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
