package bosca.ecommerce.service

import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProviderInput
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingProviderInput
import bosca.ecommerce.repository.FulfillmentCenterRepository
import bosca.ecommerce.repository.PaymentProviderRepository
import bosca.ecommerce.repository.ShippingProviderRepository
import bosca.ecommerce.repository.StoreRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Payment/shipping provider configuration registration. Both registrations are audited. */
@ServiceImplementation
class ProviderServiceImpl(
    private val paymentProviderRepository: PaymentProviderRepository,
    private val shippingProviderRepository: ShippingProviderRepository,
    private val storeRepository: StoreRepository,
    private val fulfillmentCenterRepository: FulfillmentCenterRepository,
    private val auditService: EcomAuditService,
) : ProviderService {

    override suspend fun getPaymentProvider(id: UUID): PaymentProvider? = paymentProviderRepository.get(id)

    override suspend fun getPaymentProvidersByCompany(companyId: UUID): List<PaymentProvider> =
        paymentProviderRepository.getByCompany(companyId)

    override suspend fun getShippingProvider(id: UUID): ShippingProvider? = shippingProviderRepository.get(id)

    override suspend fun getShippingProvidersByCompany(companyId: UUID): List<ShippingProvider> =
        shippingProviderRepository.getByCompany(companyId)

    // The provider SPIs are registered as named DI providers (@Provider(name = "<key>")); the
    // registration name IS the providerKey `provide(name = providerKey)` resolves by. So the registry's
    // named entries for each SPI type are exactly the keys an admin may bind. Reading names doesn't
    // instantiate the providers.
    @OptIn(InternalDI::class)
    override suspend fun paymentProviderKeys(): List<String> =
        ProviderRegistry.findAllWithNames(PaymentProcessor::class).keys.filter { it.isNotEmpty() }.sorted()

    @OptIn(InternalDI::class)
    override suspend fun shippingProviderKeys(): List<String> =
        ProviderRegistry.findAllWithNames(ShippingRateProvider::class).keys.filter { it.isNotEmpty() }.sorted()

    override suspend fun editPaymentProvider(id: UUID, input: ProviderInput, principalId: UUID?): PaymentProvider =
        transaction {
            val existing = paymentProviderRepository.get(id) ?: error("payment provider $id not found")
            val updated = paymentProviderRepository.update(
                existing.copy(
                    name = input.name,
                    providerKey = input.providerKey,
                    // Null = keep existing — the UI never reads secrets back, so it can't re-send them.
                    configuration = input.configuration ?: existing.configuration,
                ),
            ) ?: error("payment provider $id not found")
            auditService.record(
                entityType = "payment_provider",
                entityId = id,
                action = "updated",
                serializer = PaymentProvider.serializer(),
                before = existing.redactedForAudit(),
                after = updated.redactedForAudit(),
                principalId = principalId,
            )
            updated
        }

    override suspend fun editShippingProvider(id: UUID, input: ShippingProviderInput, principalId: UUID?): ShippingProvider =
        transaction {
            val existing = shippingProviderRepository.get(id) ?: error("shipping provider $id not found")
            val updated = shippingProviderRepository.update(
                existing.copy(
                    name = input.name,
                    key = input.key,
                    providerKey = input.providerKey,
                    configuration = input.configuration ?: existing.configuration,
                ),
            ) ?: error("shipping provider $id not found")
            auditService.record(
                entityType = "shipping_provider",
                entityId = id,
                action = "updated",
                serializer = ShippingProvider.serializer(),
                before = existing.redactedForAudit(),
                after = updated.redactedForAudit(),
                principalId = principalId,
            )
            updated
        }

    override suspend fun deletePaymentProvider(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = paymentProviderRepository.get(id) ?: return@transaction false
        // A store binding this provider would be left unable to take payment (the resolver returns null,
        // cart submit fails to resolve a processor). Refuse until those stores are reassigned.
        val boundStores = storeRepository.countByPaymentProvider(id)
        if (boundStores > 0) {
            error("Cannot delete this payment provider — $boundStores store(s) still use it. Reassign those stores to another provider first.")
        }
        paymentProviderRepository.softDelete(id)
        auditService.record(
            entityType = "payment_provider",
            entityId = id,
            action = "deleted",
            serializer = PaymentProvider.serializer(),
            before = existing.redactedForAudit(),
            principalId = principalId,
        )
        true
    }

    override suspend fun deleteShippingProvider(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = shippingProviderRepository.get(id) ?: return@transaction false
        val boundCenters = fulfillmentCenterRepository.countByShippingProvider(id)
        if (boundCenters > 0) {
            error("Cannot delete this shipping provider — $boundCenters fulfillment center(s) still use it. Reassign those centers to another provider first.")
        }
        shippingProviderRepository.softDelete(id)
        auditService.record(
            entityType = "shipping_provider",
            entityId = id,
            action = "deleted",
            serializer = ShippingProvider.serializer(),
            before = existing.redactedForAudit(),
            principalId = principalId,
        )
        true
    }

    override suspend fun addPaymentProvider(input: ProviderInput, principalId: UUID?): PaymentProvider = transaction {
        val provider = paymentProviderRepository.add(
            PaymentProvider(
                companyId = input.companyId,
                name = input.name,
                providerKey = input.providerKey,
                configuration = input.configuration ?: EmptyProviderConfiguration,
            ),
        )
        auditService.record(
            entityType = "payment_provider",
            entityId = provider.id,
            action = "created",
            serializer = PaymentProvider.serializer(),
            after = provider.redactedForAudit(),
            principalId = principalId,
        )
        provider
    }

    override suspend fun addShippingProvider(input: ShippingProviderInput, principalId: UUID?): ShippingProvider =
        transaction {
            val provider = shippingProviderRepository.add(
                ShippingProvider(
                    companyId = input.companyId,
                    name = input.name,
                    key = input.key,
                    providerKey = input.providerKey,
                    configuration = input.configuration ?: EmptyProviderConfiguration,
                ),
            )
            auditService.record(
                entityType = "shipping_provider",
                entityId = provider.id,
                action = "created",
                serializer = ShippingProvider.serializer(),
                after = provider.redactedForAudit(),
                principalId = principalId,
            )
            provider
        }

    /**
     * Provider [PaymentProvider.configuration] holds gateway secrets, so the audited before/after
     * snapshot strips it — otherwise secrets land in `ecom.audit` and leak through the admin audit read
     * path. The persisted row keeps the real config; only the audit copy is redacted.
     */
    private fun PaymentProvider.redactedForAudit(): PaymentProvider = copy(configuration = EmptyProviderConfiguration)

    private fun ShippingProvider.redactedForAudit(): ShippingProvider = copy(configuration = EmptyProviderConfiguration)
}
