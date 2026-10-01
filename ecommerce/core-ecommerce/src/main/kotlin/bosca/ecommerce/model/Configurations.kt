package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Polymorphic, jsonb-backed configuration/extension hierarchies. Each is a sealed `@Serializable`
 * hierarchy with `@SerialName`-tagged variants, so kotlinx serializes them with a `type`
 * discriminator and resolves the leaf serializer at compile time (GraalVM native-safe — no
 * reflective lookup). Stored as `jsonb`; services convert `JsonElement <-> typed` with explicit
 * `.serializer()` against the DI-provided `Json`.
 *
 * These hierarchies are sealed for now (all variants live in this module). When a follow-up
 * (e.g. marketplace) needs to contribute variants, the relevant hierarchy graduates to open
 * polymorphism backed by a SerializersModule.
 */

// --- ProductConfiguration: type-specific product behavior (ecom.products.configuration) ---

@Serializable
sealed interface ProductConfiguration

/** A plain product with no type-specific configuration. */
@Serializable
@SerialName("standard")
data object StandardProductConfiguration : ProductConfiguration

/** A product offered in a set of sizes (the legacy standard-products "clothing" shape). */
@Serializable
@SerialName("clothing")
data class ClothingProductConfiguration(val sizes: List<String> = emptyList()) : ProductConfiguration

/**
 * A SUBSCRIPTION product: its checkout creates a recurring purchase. Binds the plan GROUP; the cart
 * line's [SubscriptionCartItemConfiguration] selects the concrete plan within the group.
 */
@Serializable
@SerialName("subscription")
data class SubscriptionProductConfiguration(@Contextual val planGroupId: UUID) : ProductConfiguration

// --- CatalogProductExtras: per-catalog-entry extension data (ecom.catalog_products.extras) ---

@Serializable
sealed interface CatalogProductExtras

@Serializable
@SerialName("none")
data object EmptyCatalogProductExtras : CatalogProductExtras

/** Per-order quantity bounds for a catalog entry (legacy standard-products QuantityRequirements). */
@Serializable
@SerialName("quantityRequirements")
data class QuantityRequirements(val min: Int? = null, val max: Int? = null) : CatalogProductExtras

// --- PlanConfiguration: plan-specific behavior (ecom.subscription_plans.configuration) ---

@Serializable
sealed interface PlanConfiguration

@Serializable
@SerialName("standard")
data object StandardPlanConfiguration : PlanConfiguration

// --- SubscriptionExtras: subscription extension data (ecom.subscriptions.extras) ---

/**
 * An opaque, reusable payment-method reference produced by the PaymentProvider SPI's saving
 * (provider-side customer/method token) so the renewal job can charge later. Tokens only — never a
 * card PAN.
 */
@Serializable
data class SavedPaymentMethod(val providerKey: String, val token: String)

@Serializable
sealed interface SubscriptionExtras

@Serializable
@SerialName("standard")
data class StandardSubscriptionExtras(
    val savedPaymentMethod: SavedPaymentMethod? = null,
) : SubscriptionExtras

// --- CartItemConfiguration: per-line configuration (cart items jsonb) ---

@Serializable
sealed interface CartItemConfiguration

@Serializable
@SerialName("none")
data object EmptyCartItemConfiguration : CartItemConfiguration

/** A selected variant (e.g. clothing size) on a product line. */
@Serializable
@SerialName("size")
data class SizeCartItemConfiguration(val size: String) : CartItemConfiguration

/** The offered shipping rates and the customer's selection, on a SHIPPING line. */
@Serializable
@SerialName("shipping")
data class ShippingCartConfiguration(
    val selected: ShippingRate? = null,
    val options: List<ShippingRate> = emptyList(),
) : CartItemConfiguration

/** The concrete plan chosen within the product's plan group, on a SUBSCRIPTION line. */
@Serializable
@SerialName("subscription")
data class SubscriptionCartItemConfiguration(@Contextual val planId: UUID) : CartItemConfiguration

// --- ManufacturerExtras: manufacturer extension data (ecom.manufacturers.extras) ---

@Serializable
sealed interface ManufacturerExtras

/** No manufacturer extension data. The marketplace later contributes vendor-linkage variants. */
@Serializable
@SerialName("none")
data object EmptyManufacturerExtras : ManufacturerExtras

// --- AccountExtras: billing-account extension data (ecom.accounts.extras) ---

@Serializable
sealed interface AccountExtras

@Serializable
@SerialName("none")
data object EmptyAccountExtras : AccountExtras

// --- CustomerExtras: customer extension data (ecom.customers.extras) ---

@Serializable
sealed interface CustomerExtras

@Serializable
@SerialName("none")
data object EmptyCustomerExtras : CustomerExtras

// --- CartExtras: cart extension data (ecom.carts.extras) ---

@Serializable
sealed interface CartExtras

@Serializable
@SerialName("none")
data object EmptyCartExtras : CartExtras

// --- ProviderConfiguration: payment/shipping provider settings (ecom.payment_providers /
//     ecom.shipping_providers .configuration). Admin-gated; may hold gateway secrets. ---

@Serializable
sealed interface ProviderConfiguration

/** No configured settings (the default for a freshly-registered provider). */
@Serializable
@SerialName("none")
data object EmptyProviderConfiguration : ProviderConfiguration

/**
 * A generic gateway settings bag of string [values] (api keys, endpoints, ...). The placeholder shape
 * until concrete gateways (Stripe/BluePay/Shippo) contribute typed variants in their follow-up specs.
 */
@Serializable
@SerialName("keyValue")
data class KeyValueProviderConfiguration(val values: Map<String, String> = emptyMap()) : ProviderConfiguration

/** A single [KeyValueProviderConfiguration] entry — the typed GraphQL projection of the string map. */
@Serializable
data class ProviderConfigurationValue(val key: String, val value: String)
