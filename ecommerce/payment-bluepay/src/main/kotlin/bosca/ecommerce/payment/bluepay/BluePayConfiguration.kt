package bosca.ecommerce.payment.bluepay

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ecommerce.service.PaymentProcessor

/**
 * DI registration for the BluePay payment gateway. Registered under the providerKey `"bluepay"`, the
 * name `PaymentServiceImpl.processor(...)` resolves with `provide(name = providerKey)`. This module
 * has its own `ProviderRegistrarPrefix` (EcommerceBluePay → `EcommerceBluePayProviderRegistrar`) so a
 * deployment includes BluePay only by depending on this module and registering that registrar.
 */
@Providers
class BluePayConfiguration {

    @Provider(name = "bluepay", singleton = true)
    fun bluePayPaymentProcessor(): PaymentProcessor = BluePayPaymentProcessor()
}
