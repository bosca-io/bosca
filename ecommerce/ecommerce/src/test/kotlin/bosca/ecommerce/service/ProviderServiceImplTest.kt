@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.di.ProviderRegistry
import bosca.di.provides
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Provider deletion guards: a provider still bound by a store/center must not be soft-deleted (it would orphan checkout/fulfillment). */
@OptIn(ExperimentalUuidApi::class)
class ProviderServiceImplTest {

    private val paymentProviderRepository = mockk<PaymentProviderRepository>(relaxUnitFun = true)
    private val shippingProviderRepository = mockk<ShippingProviderRepository>(relaxUnitFun = true)
    private val storeRepository = mockk<StoreRepository>()
    private val fulfillmentCenterRepository = mockk<FulfillmentCenterRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ProviderServiceImpl

    private val companyId = UUID.random()
    private val paymentId = UUID.random()
    private val shippingId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { paymentProviderRepository.get(paymentId) } returns
            PaymentProvider(id = paymentId, companyId = companyId, name = "Pay", providerKey = "test")
        coEvery { shippingProviderRepository.get(shippingId) } returns
            ShippingProvider(id = shippingId, companyId = companyId, name = "Ship", key = "ship", providerKey = "test")
        service = ProviderServiceImpl(
            paymentProviderRepository, shippingProviderRepository, storeRepository, fulfillmentCenterRepository, auditService,
        )
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `deletePaymentProvider refuses while a store still binds it`() = runTest {
        coEvery { storeRepository.countByPaymentProvider(paymentId) } returns 2
        val error = assertFailsWith<IllegalStateException> { service.deletePaymentProvider(paymentId, principalId = null) }
        assertTrue(error.message!!.contains("2 store"))
        coVerify(exactly = 0) { paymentProviderRepository.softDelete(any()) }
    }

    @Test
    fun `deletePaymentProvider soft-deletes when no store binds it`() = runTest {
        coEvery { storeRepository.countByPaymentProvider(paymentId) } returns 0
        assertTrue(service.deletePaymentProvider(paymentId, principalId = null))
        coVerify(exactly = 1) { paymentProviderRepository.softDelete(paymentId) }
    }

    @Test
    fun `deletePaymentProvider returns false when the provider is absent`() = runTest {
        coEvery { paymentProviderRepository.get(paymentId) } returns null
        assertFalse(service.deletePaymentProvider(paymentId, principalId = null))
        coVerify(exactly = 0) { storeRepository.countByPaymentProvider(any()) }
        coVerify(exactly = 0) { paymentProviderRepository.softDelete(any()) }
    }

    @Test
    fun `deleteShippingProvider refuses while a fulfillment center still binds it`() = runTest {
        coEvery { fulfillmentCenterRepository.countByShippingProvider(shippingId) } returns 1
        val error = assertFailsWith<IllegalStateException> { service.deleteShippingProvider(shippingId, principalId = null) }
        assertTrue(error.message!!.contains("1 fulfillment center"))
        coVerify(exactly = 0) { shippingProviderRepository.softDelete(any()) }
    }

    @Test
    fun `deleteShippingProvider soft-deletes when no center binds it`() = runTest {
        coEvery { fulfillmentCenterRepository.countByShippingProvider(shippingId) } returns 0
        assertTrue(service.deleteShippingProvider(shippingId, principalId = null))
        coVerify(exactly = 1) { shippingProviderRepository.softDelete(shippingId) }
    }

    @Test
    fun `deleteShippingProvider returns false when the provider is absent`() = runTest {
        coEvery { shippingProviderRepository.get(shippingId) } returns null
        assertFalse(service.deleteShippingProvider(shippingId, principalId = null))
        coVerify(exactly = 0) { fulfillmentCenterRepository.countByShippingProvider(any()) }
        coVerify(exactly = 0) { shippingProviderRepository.softDelete(any()) }
    }

    @Test
    fun `provider reads delegate to the repositories`() = runTest {
        coEvery { paymentProviderRepository.getByCompany(companyId) } returns
            listOf(PaymentProvider(id = paymentId, companyId = companyId, name = "Pay", providerKey = "test"))
        coEvery { shippingProviderRepository.getByCompany(companyId) } returns
            listOf(ShippingProvider(id = shippingId, companyId = companyId, name = "Ship", key = "ship", providerKey = "test"))

        assertEquals(paymentId, service.getPaymentProvider(paymentId)?.id)
        assertEquals(1, service.getPaymentProvidersByCompany(companyId).size)
        assertEquals(shippingId, service.getShippingProvider(shippingId)?.id)
        assertEquals(1, service.getShippingProvidersByCompany(companyId).size)
    }

    @Test
    fun `provider key enumeration lists registered SPI names, sorted`() = runTest {
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "stripe", singleton = true) { TestPaymentProcessor() }
        provides<PaymentProcessor>(name = "bluepay", singleton = true) { TestPaymentProcessor() }
        provides<ShippingRateProvider>(name = "fixed-rate", singleton = true) { FixedRateShippingRateProvider() }

        assertEquals(listOf("bluepay", "stripe"), service.paymentProviderKeys())
        assertEquals(listOf("fixed-rate"), service.shippingProviderKeys())
        ProviderRegistry.clear()
    }

    @Test
    fun `editPaymentProvider applies the input and keeps the existing configuration when none is sent`() = runTest {
        val updated = slot<PaymentProvider>()
        coEvery { paymentProviderRepository.update(capture(updated)) } answers { updated.captured }

        // configuration = null -> keep existing (the UI never reads secrets back).
        val result = service.editPaymentProvider(paymentId, ProviderInput(companyId, "Renamed", "test", configuration = null), principalId = null)

        assertEquals("Renamed", result.name)
        coVerify(exactly = 1) {
            auditService.record<PaymentProvider>(entityType = "payment_provider", entityId = paymentId, action = "updated", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `editPaymentProvider replaces the configuration when one is sent`() = runTest {
        coEvery { paymentProviderRepository.update(any()) } answers { firstArg() }
        val result = service.editPaymentProvider(paymentId, ProviderInput(companyId, "Renamed", "test", configuration = EmptyProviderConfiguration), principalId = null)
        assertEquals("Renamed", result.name)
    }

    @Test
    fun `editPaymentProvider throws when the provider is absent`() = runTest {
        coEvery { paymentProviderRepository.get(paymentId) } returns null
        assertFailsWith<IllegalStateException> { service.editPaymentProvider(paymentId, ProviderInput(companyId, "X", "test"), principalId = null) }
    }

    @Test
    fun `editShippingProvider applies the input and audits`() = runTest {
        coEvery { shippingProviderRepository.update(any()) } answers { firstArg() }
        val result = service.editShippingProvider(shippingId, ShippingProviderInput(companyId, "Renamed", "ship", "test"), principalId = null)
        assertEquals("Renamed", result.name)
        coVerify(exactly = 1) {
            auditService.record<ShippingProvider>(entityType = "shipping_provider", entityId = shippingId, action = "updated", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `editShippingProvider throws when the provider is absent`() = runTest {
        coEvery { shippingProviderRepository.get(shippingId) } returns null
        assertFailsWith<IllegalStateException> { service.editShippingProvider(shippingId, ShippingProviderInput(companyId, "X", "ship", "test"), principalId = null) }
    }

    @Test
    fun `editPaymentProvider throws when the row vanishes mid-update`() = runTest {
        coEvery { paymentProviderRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.editPaymentProvider(paymentId, ProviderInput(companyId, "X", "test"), principalId = null) }
    }

    @Test
    fun `editShippingProvider throws when the row vanishes mid-update`() = runTest {
        coEvery { shippingProviderRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.editShippingProvider(shippingId, ShippingProviderInput(companyId, "X", "ship", "test"), principalId = null) }
    }

    @Test
    fun `editShippingProvider replaces the configuration when one is sent`() = runTest {
        val updated = slot<ShippingProvider>()
        coEvery { shippingProviderRepository.update(capture(updated)) } answers { updated.captured }
        service.editShippingProvider(shippingId, ShippingProviderInput(companyId, "Renamed", "ship", "test", configuration = EmptyProviderConfiguration), principalId = null)
        assertEquals(EmptyProviderConfiguration, updated.captured.configuration)
    }

    @Test
    fun `addPaymentProvider persists with the sent configuration and audits created`() = runTest {
        val added = slot<PaymentProvider>()
        coEvery { paymentProviderRepository.add(capture(added)) } answers { added.captured.copy(id = paymentId) }

        val result = service.addPaymentProvider(ProviderInput(companyId, "Pay", "test", configuration = EmptyProviderConfiguration), principalId = null)

        assertEquals(paymentId, result.id)
        assertEquals(EmptyProviderConfiguration, added.captured.configuration)
        coVerify(exactly = 1) {
            auditService.record<PaymentProvider>(entityType = "payment_provider", entityId = paymentId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `addPaymentProvider defaults to the empty configuration when none is sent`() = runTest {
        val added = slot<PaymentProvider>()
        coEvery { paymentProviderRepository.add(capture(added)) } answers { added.captured.copy(id = paymentId) }
        service.addPaymentProvider(ProviderInput(companyId, "Pay", "test", configuration = null), principalId = null)
        assertEquals(EmptyProviderConfiguration, added.captured.configuration)
    }

    @Test
    fun `addPaymentProvider keeps the secret config on the row but redacts it from the audit snapshot`() = runTest {
        val secret = bosca.ecommerce.model.KeyValueProviderConfiguration(mapOf("secretKey" to "sk_live_TOPSECRET"))
        val added = slot<PaymentProvider>()
        coEvery { paymentProviderRepository.add(capture(added)) } answers { added.captured.copy(id = paymentId) }
        val auditAfter = slot<PaymentProvider>()
        coEvery {
            auditService.record<PaymentProvider>(
                entityType = any(), entityId = any(), action = any(), serializer = any(),
                before = any(), after = capture(auditAfter), principalId = any(), profileId = any(), storeId = any(), details = any(),
            )
        } returns Unit

        service.addPaymentProvider(ProviderInput(companyId, "Pay", "stripe", configuration = secret), principalId = null)

        assertEquals(secret, added.captured.configuration) // the persisted row keeps the real secret
        assertEquals(EmptyProviderConfiguration, auditAfter.captured.configuration) // the audit snapshot does NOT
    }

    @Test
    fun `addShippingProvider persists with the sent configuration and audits created`() = runTest {
        val added = slot<ShippingProvider>()
        coEvery { shippingProviderRepository.add(capture(added)) } answers { added.captured.copy(id = shippingId) }

        val result = service.addShippingProvider(ShippingProviderInput(companyId, "Ship", "ship", "test", configuration = EmptyProviderConfiguration), principalId = null)

        assertEquals(shippingId, result.id)
        assertEquals(EmptyProviderConfiguration, added.captured.configuration)
    }

    @Test
    fun `addShippingProvider defaults to the empty configuration when none is sent`() = runTest {
        val added = slot<ShippingProvider>()
        coEvery { shippingProviderRepository.add(capture(added)) } answers { added.captured.copy(id = shippingId) }
        service.addShippingProvider(ShippingProviderInput(companyId, "Ship", "ship", "test", configuration = null), principalId = null)
        assertEquals(EmptyProviderConfiguration, added.captured.configuration)
    }

    @Test
    fun `editShippingProvider keeps the existing configuration when none is sent`() = runTest {
        // The existing provider already carries a key-value config; sending null must preserve it.
        val keep = bosca.ecommerce.model.KeyValueProviderConfiguration(mapOf("amount" to "9.99"))
        coEvery { shippingProviderRepository.get(shippingId) } returns
            ShippingProvider(id = shippingId, companyId = companyId, name = "Ship", key = "ship", providerKey = "test", configuration = keep)
        val updated = slot<ShippingProvider>()
        coEvery { shippingProviderRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.editShippingProvider(shippingId, ShippingProviderInput(companyId, "Renamed", "ship", "test", configuration = null), principalId = null)

        assertEquals("Renamed", result.name)
        assertEquals(keep, updated.captured.configuration)
    }

    @Test
    fun `provider key enumeration drops the unnamed default entry`() = runTest {
        ProviderRegistry.clear()
        // An unnamed (type-keyed) provider surfaces under the empty-string key in findAllWithNames and is
        // dropped by `filter { it.isNotEmpty() }`; only the named entries are returned.
        provides<PaymentProcessor>(singleton = true) { TestPaymentProcessor() }
        provides<PaymentProcessor>(name = "stripe", singleton = true) { TestPaymentProcessor() }
        provides<ShippingRateProvider>(singleton = true) { FixedRateShippingRateProvider() }

        assertEquals(listOf("stripe"), service.paymentProviderKeys())
        assertEquals(emptyList(), service.shippingProviderKeys())
        ProviderRegistry.clear()
    }
}
