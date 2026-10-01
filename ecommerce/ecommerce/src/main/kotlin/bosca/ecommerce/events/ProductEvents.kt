package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Product lifecycle events (`bosca.ecommerce.product.*`), emitted from `ProductServiceImpl`. These
 * complement the content-side search reindex (driven by the backing Metadata): consumers
 * that care about the commerce product (catalog sync, pricing automations) subscribe here.
 */
@Serializable
sealed class ProductEvent : Event {
    @Contextual
    abstract val productId: UUID

    @Contextual
    abstract val companyId: UUID

    override fun identityKey(): Any = productId
}

const val PRODUCT_CREATED_CHANNEL = "bosca.ecommerce.product.created"
const val PRODUCT_UPDATED_CHANNEL = "bosca.ecommerce.product.updated"
const val PRODUCT_DELETED_CHANNEL = "bosca.ecommerce.product.deleted"

@JobEvent(
    jobs = [],
    pubsubChannel = PRODUCT_CREATED_CHANNEL,
    displayName = "Product Created",
    description = "A commerce product was created.",
)
@Serializable
data class ProductCreated(
    @Contextual override val productId: UUID,
    @Contextual override val companyId: UUID,
) : ProductEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = PRODUCT_UPDATED_CHANNEL,
    displayName = "Product Updated",
    description = "A commerce product's definition was updated.",
)
@Serializable
data class ProductUpdated(
    @Contextual override val productId: UUID,
    @Contextual override val companyId: UUID,
) : ProductEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = PRODUCT_DELETED_CHANNEL,
    displayName = "Product Deleted",
    description = "A commerce product was soft-deleted (and removed from search).",
)
@Serializable
data class ProductDeleted(
    @Contextual override val productId: UUID,
    @Contextual override val companyId: UUID,
) : ProductEvent()
