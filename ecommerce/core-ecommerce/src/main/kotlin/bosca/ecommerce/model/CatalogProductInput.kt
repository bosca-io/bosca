package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Places a product in a catalog as a sellable entry, or edits one. The entry [type] is derived from
 * the product. [starts]/[ends] default to now / the far-future window when null.
 */
@Serializable
data class CatalogProductInput(
    @Contextual
    val catalogId: UUID,
    @Contextual
    val productId: UUID,
    val price: Money,
    val taxable: Boolean = true,
    @Contextual
    val starts: OffsetDateTime? = null,
    @Contextual
    val ends: OffsetDateTime? = null,
    val extras: CatalogProductExtras? = null,
)
