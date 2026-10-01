package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A selling context: binds a company, the catalog it sells, the default payment provider, the
 * shipping-line catalog entry, and cart-expiration policy. Carts, payments, plans, and promotions
 * are all store-scoped. [identifier] is a plain unique storefront slug (the legacy minion-instance
 * coupling is not ported). [cartExpirationSeconds] is how long an idle cart lives before the
 * expiration sweep reclaims it.
 */
@BatchKey("id")
@Serializable
data class Store(
    @Contextual
    val id: UUID = UUID.NIL,
    val identifier: String,
    val name: String,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("catalog_id")
    val catalogId: UUID,
    val type: StoreType,
    @Contextual
    @ColumnName("payment_provider_id")
    val paymentProviderId: UUID,
    @Contextual
    @ColumnName("shipping_catalog_product_id")
    val shippingCatalogProductId: UUID,
    @ColumnName("cart_expiration_seconds")
    val cartExpirationSeconds: Int = 86400,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
