package bosca.ecommerce.shipping.shippo

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ecommerce.service.ShippingRateProvider

/**
 * DI registration for the Shippo shipping provider. Registered under the providerKey `"shippo"`, which
 * `ShippingServiceImpl` resolves with `provide(name = providerKey)`. Its own `ProviderRegistrarPrefix`
 * (EcommerceShippo → `EcommerceShippoProviderRegistrar`) lets a deployment include Shippo only by
 * depending on this module and registering that registrar.
 */
@Providers
class ShippoConfiguration {

    @Provider(name = "shippo", singleton = true)
    fun shippoShippingRateProvider(): ShippingRateProvider = ShippoShippingRateProvider()
}
