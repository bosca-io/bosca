package bosca.ecommerce.graphql

import bosca.ecommerce.model.ClothingProductConfiguration
import bosca.ecommerce.model.EmptyAccountExtras
import bosca.ecommerce.model.EmptyCartItemConfiguration
import bosca.ecommerce.model.EmptyCatalogProductExtras
import bosca.ecommerce.model.EmptyCustomerExtras
import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.ProviderConfigurationValue
import bosca.ecommerce.model.QuantityRequirements
import bosca.ecommerce.model.ShippingCartConfiguration
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.SizeCartItemConfiguration
import bosca.ecommerce.model.StandardPlanConfiguration
import bosca.ecommerce.model.StandardProductConfiguration
import bosca.ecommerce.model.SubscriptionCartItemConfiguration
import bosca.ecommerce.model.SubscriptionProductConfiguration
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/**
 * Field wiring for the members of the polymorphic configuration/extras GraphQL unions
 * (`ProductConfiguration`, `CartItemConfiguration`, `CatalogProductExtras`, `ManufacturerExtras`,
 * `AccountExtras`, `CustomerExtras`, `ProviderConfiguration`, `PlanConfiguration`). Each member type's
 * GraphQL name is its Kotlin class simple name (resolved by the union's `DispatcherTypeResolver`), and
 * each exposes its kotlinx `type` discriminator so clients can branch without an inline fragment.
 * Inputs stay a `JSON` scalar decoded into the sealed type by the controller dispatcher (the `Rule`
 * pattern), so the `type` value here matches the `@SerialName` accepted on input.
 */

// --- ProductConfiguration ---

@TypeController
class StandardProductConfigurationController : GraphQLController<StandardProductConfiguration> {
    @Field fun type(source: StandardProductConfiguration): String = "standard"
}

@TypeController
class ClothingProductConfigurationController : GraphQLController<ClothingProductConfiguration> {
    @Field fun type(source: ClothingProductConfiguration): String = "clothing"
    @Field fun sizes(source: ClothingProductConfiguration): List<String> = source.sizes
}

@TypeController
class SubscriptionProductConfigurationController : GraphQLController<SubscriptionProductConfiguration> {
    @Field fun type(source: SubscriptionProductConfiguration): String = "subscription"
    @Field fun planGroupId(source: SubscriptionProductConfiguration): UUID = source.planGroupId
}

// --- CartItemConfiguration ---

@TypeController
class EmptyCartItemConfigurationController : GraphQLController<EmptyCartItemConfiguration> {
    @Field fun type(source: EmptyCartItemConfiguration): String = "none"
}

@TypeController
class SizeCartItemConfigurationController : GraphQLController<SizeCartItemConfiguration> {
    @Field fun type(source: SizeCartItemConfiguration): String = "size"
    @Field fun size(source: SizeCartItemConfiguration): String = source.size
}

@TypeController
class ShippingCartConfigurationController : GraphQLController<ShippingCartConfiguration> {
    @Field fun type(source: ShippingCartConfiguration): String = "shipping"
    @Field fun selected(source: ShippingCartConfiguration): ShippingRate? = source.selected
    @Field fun options(source: ShippingCartConfiguration): List<ShippingRate> = source.options
}

@TypeController
class SubscriptionCartItemConfigurationController : GraphQLController<SubscriptionCartItemConfiguration> {
    @Field fun type(source: SubscriptionCartItemConfiguration): String = "subscription"
    @Field fun planId(source: SubscriptionCartItemConfiguration): UUID = source.planId
}

// --- CatalogProductExtras ---

@TypeController
class EmptyCatalogProductExtrasController : GraphQLController<EmptyCatalogProductExtras> {
    @Field fun type(source: EmptyCatalogProductExtras): String = "none"
}

@TypeController
class QuantityRequirementsController : GraphQLController<QuantityRequirements> {
    @Field fun type(source: QuantityRequirements): String = "quantityRequirements"
    @Field fun min(source: QuantityRequirements): Int? = source.min
    @Field fun max(source: QuantityRequirements): Int? = source.max
}

// --- ManufacturerExtras / AccountExtras / CustomerExtras (Empty-only for now) ---

@TypeController
class EmptyManufacturerExtrasController : GraphQLController<EmptyManufacturerExtras> {
    @Field fun type(source: EmptyManufacturerExtras): String = "none"
}

@TypeController
class EmptyAccountExtrasController : GraphQLController<EmptyAccountExtras> {
    @Field fun type(source: EmptyAccountExtras): String = "none"
}

@TypeController
class EmptyCustomerExtrasController : GraphQLController<EmptyCustomerExtras> {
    @Field fun type(source: EmptyCustomerExtras): String = "none"
}

// --- ProviderConfiguration ---

@TypeController
class EmptyProviderConfigurationController : GraphQLController<EmptyProviderConfiguration> {
    @Field fun type(source: EmptyProviderConfiguration): String = "none"
}

@TypeController
class KeyValueProviderConfigurationController : GraphQLController<KeyValueProviderConfiguration> {
    @Field fun type(source: KeyValueProviderConfiguration): String = "keyValue"
    @Field fun values(source: KeyValueProviderConfiguration): List<ProviderConfigurationValue> =
        source.values.map { (key, value) -> ProviderConfigurationValue(key, value) }
}

@TypeController
class ProviderConfigurationValueController : GraphQLController<ProviderConfigurationValue> {
    @Field fun key(source: ProviderConfigurationValue): String = source.key
    @Field fun value(source: ProviderConfigurationValue): String = source.value
}

// --- PlanConfiguration ---

@TypeController
class StandardPlanConfigurationController : GraphQLController<StandardPlanConfiguration> {
    @Field fun type(source: StandardPlanConfiguration): String = "standard"
}
