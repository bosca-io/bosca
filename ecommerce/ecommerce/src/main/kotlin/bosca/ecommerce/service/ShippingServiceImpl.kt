package bosca.ecommerce.service

import bosca.di.provide
import bosca.ecommerce.model.Address
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.WeightUnit
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Resolves the company's configured shipping providers (by `providerKey` -> a DI-registered
 * [ShippingRateProvider]) and aggregates their rate quotes for the cart. The caller resolves the
 * carrier context the provider needs — origin (the company's fulfillment center) and destination (the
 * cart) addresses and the estimated parcels (from each physical line's product dimensions, in the
 * company's units) — so providers stay thin. Selection re-quotes and matches by token, so the line
 * price is always provider-sourced, never client-sent.
 */
@ServiceImplementation
class ShippingServiceImpl(
    private val cartService: CartService,
    private val providerService: ProviderService,
    private val fulfillmentService: FulfillmentService,
    private val catalogProductService: CatalogProductService,
    private val productService: ProductService,
    private val companyService: CompanyService,
) : ShippingService {

    override suspend fun rates(cartId: UUID): List<ShippingRate> {
        val cart = cartService.get(cartId) ?: error("cart $cartId not found")
        val addresses = cartService.getAddresses(cartId)
        val destinationRow = addresses.firstOrNull { it.type == AddressType.SHIPPING }
            ?: addresses.firstOrNull { it.type == AddressType.BILLING }
        val destination = if (destinationRow != null) destinationRow.toAddress() else null
        val origin = fulfillmentService.getCentersByCompany(cart.companyId).firstOrNull()?.let {
            Address(it.address1, it.address2, it.city, it.state, it.country, it.zip)
        }
        val company = companyService.get(cart.companyId)
        val lengthUnit = if (company != null) company.lengthUnit else LengthUnit.INCHES
        val weightUnit = if (company != null) company.weightUnit else WeightUnit.POUNDS
        val parcels = cart.items.filter { it.type == ProductType.PHYSICAL }.mapNotNull { item ->
            val product = catalogProductService.get(item.catalogProductId)?.let { productService.get(it.productId) } ?: return@mapNotNull null
            Parcel(product.length, product.width, product.height, product.weight * item.quantity, lengthUnit, weightUnit)
        }
        // Each config names the provider it uses; resolve that one directly (keyed lookup, no scan).
        return providerService.getShippingProvidersByCompany(cart.companyId).flatMap { config ->
            provide<ShippingRateProvider>(name = config.providerKey).rates(config, cart, origin, destination, parcels)
        }
    }

    override suspend fun selectRate(cartId: UUID, token: String, principalId: UUID?): Cart {
        val rates = rates(cartId)
        val rate = rates.firstOrNull { it.token == token } ?: error("shipping rate $token is not available")
        return cartService.setShipping(cartId, rate, rates, principalId)
    }

    private fun CartAddress.toAddress(): Address = Address(address1, address2, city, state, country, zip)
}
