@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapReceipt
import bosca.ecommerce.model.IapRedeemInput
import bosca.ecommerce.model.IapRedemptionStatus
import bosca.ecommerce.model.IapTransaction
import bosca.ecommerce.model.IapVerificationStatus
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.repository.IapTransactionRepository
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**IAP entitlement redemption — verdict mapping, grant-once, and transaction-id replay dedupe. */
@OptIn(ExperimentalUuidApi::class)
class IapEntitlementServiceImplTest {

    private val subscriptionService = mockk<SubscriptionService>()
    private val iapTransactionRepository = mockk<IapTransactionRepository>(relaxUnitFun = true)
    private val config = mockk<ApplicationConfig>(relaxed = true)
    private val application = mockk<BoscaApplication>()
    private val appleVerifier = mockk<bosca.ecommerce.service.IapReceiptVerifier>()
    private val googleVerifier = mockk<bosca.ecommerce.service.IapReceiptVerifier>()
    private lateinit var service: IapEntitlementServiceImpl

    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val planId = UUID.random()
    private val planGroupId = UUID.random()
    private val subId = UUID.random()
    private val claimId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<bosca.ecommerce.service.IapReceiptVerifier>(name = "iap-apple", singleton = true) { appleVerifier }
        provides<bosca.ecommerce.service.IapReceiptVerifier>(name = "iap-google", singleton = true) { googleVerifier }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        // Config returns a usable string for any path (relaxed default is "", which is fine for these tests).
        every { config.propertyOrNull(any()) } returns null
        // The service reads config off the injected BoscaApplication (ApplicationConfig is not DI-resolvable).
        every { application.environment } returns BoscaApplication.Environment(config)
        // setSubscription returns a (non-Unit) IapTransaction the service ignores; stub it so the mock answers.
        coEvery { iapTransactionRepository.setSubscription(any(), any()) } answers { claim(subscriptionId = secondArg()) }
        service = IapEntitlementServiceImpl(subscriptionService, iapTransactionRepository, application)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun input(platform: IapPlatform = IapPlatform.IOS, productId: String? = "prod.x", packageName: String? = null) =
        IapRedeemInput(storeId = storeId, accountId = accountId, planId = planId, platform = platform, token = "tok", productId = productId, packageName = packageName)

    private fun grantedSubscription() = Subscription(
        id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = planGroupId,
        status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS, external = true,
    )

    private fun claim(subscriptionId: UUID? = null) = IapTransaction(
        id = claimId, platform = IapPlatform.IOS, transactionId = "tx-1", accountId = accountId, planId = planId, subscriptionId = subscriptionId,
    )

    @Test
    fun `a valid receipt grants the entitlement exactly once and records the subscription`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VALID, transactionId = "tx-1", productId = "prod.x")
        coEvery { iapTransactionRepository.insertIfAbsent(IapPlatform.IOS, "tx-1", accountId, planId, "prod.x") } returns claim()
        coEvery { subscriptionService.grantExternal(storeId, accountId, planId, null) } returns grantedSubscription()

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.GRANTED, result.status)
        assertEquals(subId, result.subscriptionId)
        assertEquals("tx-1", result.transactionId)
        coVerify(exactly = 1) { subscriptionService.grantExternal(storeId, accountId, planId, null) }
        coVerify(exactly = 1) { iapTransactionRepository.setSubscription(claimId, subId) }
    }

    @Test
    fun `a replayed receipt is rejected without granting again`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VALID, transactionId = "tx-1", productId = "prod.x")
        // The transaction was already claimed: insertIfAbsent returns null, and the prior claim carries the grant.
        coEvery { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) } returns null
        coEvery { iapTransactionRepository.getByPlatformAndTransactionId(IapPlatform.IOS, "tx-1") } returns claim(subscriptionId = subId)

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.ALREADY_REDEEMED, result.status)
        assertEquals(subId, result.subscriptionId)
        assertEquals("tx-1", result.transactionId)
        coVerify(exactly = 0) { subscriptionService.grantExternal(any(), any(), any(), any()) }
        coVerify(exactly = 0) { iapTransactionRepository.setSubscription(any(), any()) }
    }

    @Test
    fun `a replay whose prior claim never recorded a subscription reports a null subscription`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VALID, transactionId = "tx-1")
        coEvery { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) } returns null
        coEvery { iapTransactionRepository.getByPlatformAndTransactionId(IapPlatform.IOS, "tx-1") } returns null

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.ALREADY_REDEEMED, result.status)
        assertNull(result.subscriptionId)
    }

    @Test
    fun `an invalid receipt does not record or grant`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.INVALID, transactionId = "tx-bad")

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.INVALID, result.status)
        assertEquals("tx-bad", result.transactionId)
        coVerify(exactly = 0) { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { subscriptionService.grantExternal(any(), any(), any(), any()) }
    }

    @Test
    fun `a not-found receipt does not record or grant`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.NOT_FOUND)

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.NOT_FOUND, result.status)
        assertNull(result.subscriptionId)
        coVerify(exactly = 0) { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unavailable verification does not record or grant`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VERIFICATION_UNAVAILABLE)

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.VERIFICATION_UNAVAILABLE, result.status)
        coVerify(exactly = 0) { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { subscriptionService.grantExternal(any(), any(), any(), any()) }
    }

    @Test
    fun `redeem degrades to VERIFICATION_UNAVAILABLE when the iap module is not installed`() = runTest {
        // No IAP verifier registered (the iap provider module absent) — redeem must not throw a raw
        // MissingProviderException; it reports an unreached verdict and grants nothing.
        ProviderRegistry.clear()

        val result = service.redeem(input(), principalId = null)

        assertEquals(IapRedemptionStatus.VERIFICATION_UNAVAILABLE, result.status)
        coVerify(exactly = 0) { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { subscriptionService.grantExternal(any(), any(), any(), any()) }
    }

    @Test
    fun `an android redemption uses the google verifier with google credentials`() = runTest {
        every { config.propertyOrNull("ecom.iap.google.email") } returns configValue("svc@acct")
        every { config.propertyOrNull("ecom.iap.google.privateKey") } returns configValue("PEM")
        every { config.propertyOrNull("ecom.iap.google.packageName") } returns configValue("com.app")
        coEvery { googleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.ANDROID, status = IapVerificationStatus.VALID, transactionId = "g-tx", productId = "prod.x")
        coEvery { iapTransactionRepository.insertIfAbsent(IapPlatform.ANDROID, "g-tx", accountId, planId, "prod.x") } returns
            claim().copy(platform = IapPlatform.ANDROID, transactionId = "g-tx")
        coEvery { subscriptionService.grantExternal(storeId, accountId, planId, null) } returns grantedSubscription()
        val creds = slot<Map<String, String>>()
        val sandbox = slot<Boolean>()

        val result = service.redeem(input(platform = IapPlatform.ANDROID, productId = "prod.x", packageName = "com.app"), principalId = null)

        assertEquals(IapRedemptionStatus.GRANTED, result.status)
        coVerify(exactly = 1) {
            googleVerifier.verify("tok", "prod.x", "com.app", capture(creds), capture(sandbox))
        }
        coVerify(exactly = 0) { appleVerifier.verify(any(), any(), any(), any(), any()) }
        assertEquals(mapOf("email" to "svc@acct", "privateKey" to "PEM", "packageName" to "com.app"), creds.captured)
        assertEquals(false, sandbox.captured) // Android verifies against a single endpoint
    }

    @Test
    fun `an ios redemption passes the apple shared secret and sandbox flag`() = runTest {
        every { config.propertyOrNull("ecom.iap.apple.password") } returns configValue("secret")
        every { config.propertyOrNull("ecom.iap.apple.sandbox") } returns configValue("true")
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VALID, transactionId = "tx-1")
        coEvery { iapTransactionRepository.insertIfAbsent(any(), any(), any(), any(), any()) } returns claim()
        coEvery { subscriptionService.grantExternal(any(), any(), any(), any()) } returns grantedSubscription()
        val creds = slot<Map<String, String>>()
        val sandbox = slot<Boolean>()

        service.redeem(input(), principalId = null)

        coVerify(exactly = 1) { appleVerifier.verify("tok", "prod.x", null, capture(creds), capture(sandbox)) }
        assertEquals(mapOf("password" to "secret"), creds.captured)
        assertEquals(true, sandbox.captured)
    }

    @Test
    fun `a valid receipt with no transaction id throws`() = runTest {
        coEvery { appleVerifier.verify(any(), any(), any(), any(), any()) } returns
            IapReceipt(platform = IapPlatform.IOS, status = IapVerificationStatus.VALID, transactionId = null)
        assertFailsWith<IllegalStateException> { service.redeem(input(), principalId = null) }
    }

    private fun configValue(value: String) = bosca.server.config.ConfigValue(
        kotlinx.serialization.json.JsonPrimitive(value),
        kotlinx.serialization.json.Json,
    )
}
