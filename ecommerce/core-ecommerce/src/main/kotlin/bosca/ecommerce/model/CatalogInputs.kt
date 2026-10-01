package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates or edits a catalog. [key] is unique per company. */
@Serializable
data class CatalogInput(
    @Contextual
    val companyId: UUID,
    val key: String,
    val name: String,
    /** The ISO-4217 currency this catalog (price book) is priced in. Defaults to USD. */
    val currency: String = "USD",
)

/** Creates a manufacturer. [extras] is a serialized [ManufacturerExtras]. */
@Serializable
data class ManufacturerInput(
    @Contextual
    val companyId: UUID,
    val name: String,
    val extras: ManufacturerExtras? = null,
)
