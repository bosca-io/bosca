package bosca.ecommerce.payment.stripe

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ecommerce.service.PaymentProcessor

/**
 * DI registration for the Stripe payment gateway. Registered under the providerKey `"stripe"`, which
 * `PaymentServiceImpl.processor(...)` resolves with `provide(name = providerKey)`. Its own
 * `ProviderRegistrarPrefix` (EcommerceStripe → `EcommerceStripeProviderRegistrar`) lets a deployment
 * include Stripe only by depending on this module and registering that registrar.
 */
@Providers
class StripeConfiguration {

    @Provider(name = "stripe", singleton = true)
    fun stripePaymentProcessor(): PaymentProcessor = StripePaymentProcessor()
}
