package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates or edits a store. */
@Serializable
data class StoreInput(
    @Contextual
    val companyId: UUID,
    val identifier: String,
    val name: String,
    @Contextual
    val catalogId: UUID,
    val type: StoreType,
    @Contextual
    val paymentProviderId: UUID,
    @Contextual
    val shippingCatalogProductId: UUID,
    val cartExpirationSeconds: Int = 86400,
)

/** Registers a payment provider configuration. */
@Serializable
data class ProviderInput(
    @Contextual
    val companyId: UUID,
    val name: String,
    val providerKey: String,
    val configuration: ProviderConfiguration? = null,
)

/** Registers a shipping provider configuration. */
@Serializable
data class ShippingProviderInput(
    @Contextual
    val companyId: UUID,
    val name: String,
    val key: String,
    val providerKey: String,
    val configuration: ProviderConfiguration? = null,
)
