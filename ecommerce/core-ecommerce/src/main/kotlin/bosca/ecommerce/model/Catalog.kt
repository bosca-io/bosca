package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A priceable grouping of products. A store sells exactly one catalog; the same product can appear
 * in many catalogs at different prices via CatalogProduct. [key] is unique per company.
 */
@BatchKey("id")
@Serializable
data class Catalog(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val key: String,
    val name: String,
    /** The ISO-4217 currency this catalog (price book) is priced in. The stores selling it inherit it. */
    val currency: String = "USD",
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
