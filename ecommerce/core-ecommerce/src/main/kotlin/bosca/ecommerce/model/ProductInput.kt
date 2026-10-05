package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Creates or edits a product. [title]/[description] seed the backing content Metadata document on
 * create; afterwards content is edited and published through the content module, and the product's
 * pinned version follows the publish automatically. [configuration] is a serialized
 * [ProductConfiguration] (e.g. SubscriptionProductConfiguration for SUBSCRIPTION products).
 */
@Serializable
data class ProductInput(
    @Contextual
    val companyId: UUID,
    @Contextual
    val manufacturerId: UUID,
    val manufacturerSku: String,
    val type: ProductType,
    val configuration: ProductConfiguration? = null,
    /** Shipping weight; intrinsic to the product. */
    val weight: Double = 1.0,
    /** Shipping dimensions (unitless; 0 = unknown). The packer uses these to box for density. */
    val width: Double = 0.0,
    val height: Double = 0.0,
    val length: Double = 0.0,
    val title: String,
    val description: String? = null,
)
