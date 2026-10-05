package bosca.ecommerce.service

import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProviderInput
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingProviderInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Payment/shipping provider configuration rows. Behavior binds via the DI-registered provider SPI
 * keyed by `providerKey` (the PaymentProvider and ShippingProvider SPIs); this
 * service manages the config rows the stores and payment/shipping engines resolve.
 */
interface ProviderService : Service {

    /** A payment provider configuration by id. */
    suspend fun getPaymentProvider(id: UUID): PaymentProvider?

    /** Every payment provider configuration for a company. */
    suspend fun getPaymentProvidersByCompany(companyId: UUID): List<PaymentProvider>

    /** A shipping provider configuration by id. */
    suspend fun getShippingProvider(id: UUID): ShippingProvider?

    /** Every shipping provider configuration for a company. */
    suspend fun getShippingProvidersByCompany(companyId: UUID): List<ShippingProvider>

    /** Register a payment provider configuration. */
    suspend fun addPaymentProvider(input: ProviderInput, principalId: UUID?): PaymentProvider

    /** Register a shipping provider configuration. */
    suspend fun addShippingProvider(input: ShippingProviderInput, principalId: UUID?): ShippingProvider

    /**
     * Edit a payment provider. A null [ProviderInput.configuration] KEEPS the existing settings — so
     * the admin UI (which never reads secrets back) can edit name/key without wiping credentials.
     */
    suspend fun editPaymentProvider(id: UUID, input: ProviderInput, principalId: UUID?): PaymentProvider

    /** Edit a shipping provider. A null [ShippingProviderInput.configuration] keeps existing settings. */
    suspend fun editShippingProvider(id: UUID, input: ShippingProviderInput, principalId: UUID?): ShippingProvider

    /**
     * The DI-registered payment provider implementation keys — the valid values for
     * [ProviderInput.providerKey]. Sourced from the provider SPI registry, not a config table.
     */
    suspend fun paymentProviderKeys(): List<String>

    /** The DI-registered shipping provider implementation keys (valid [ShippingProviderInput.providerKey]). */
    suspend fun shippingProviderKeys(): List<String>

    /** Soft-delete a payment provider (callers must ensure no store still binds it). Returns false if absent. */
    suspend fun deletePaymentProvider(id: UUID, principalId: UUID?): Boolean

    /** Soft-delete a shipping provider (callers must ensure no store/center still binds it). */
    suspend fun deleteShippingProvider(id: UUID, principalId: UUID?): Boolean
}
