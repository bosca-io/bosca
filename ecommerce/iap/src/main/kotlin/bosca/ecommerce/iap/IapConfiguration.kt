package bosca.ecommerce.iap

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.ecommerce.service.IapReceiptVerifier

/**
 * DI registration for the in-app-purchase receipt verifiers. Each store verifier is registered under its
 * own key (`iap-apple`, `iap-google`), resolved with `provide(name = key)`. Its own
 * `ProviderRegistrarPrefix` (EcommerceIap → `EcommerceIapProviderRegistrar`) lets a deployment include
 * IAP support only by depending on this module and registering that registrar.
 */
@Providers
class IapConfiguration {

    @Provider(name = "iap-apple", singleton = true)
    fun appleReceiptVerifier(): IapReceiptVerifier = AppleReceiptVerifier()

    @Provider(name = "iap-google", singleton = true)
    fun googleReceiptVerifier(): IapReceiptVerifier = GoogleReceiptVerifier()
}
