package bosca.ecommerce.graphql

import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapRedeemInput
import bosca.ecommerce.model.IapRedemptionResult
import bosca.ecommerce.model.IapRedemptionStatus
import bosca.ecommerce.service.IapEntitlementService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** IAP redemption namespace: redeem gates on admin and forwards the principal id to the entitlement service. */
@OptIn(ExperimentalUuidApi::class)
class IapMutationControllerTest {

    private val iapEntitlementService = mockk<IapEntitlementService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = IapMutationController(iapEntitlementService, groups)

    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val planId = UUID.random()
    private val subId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = IapRedeemInput(
        storeId = storeId, accountId = accountId, planId = planId, platform = IapPlatform.IOS, token = "tok",
    )

    @Test
    fun `redeem gates on admin then delegates to the service`() = runTest {
        coEvery { iapEntitlementService.redeem(any(), null) } returns
            IapRedemptionResult(IapRedemptionStatus.GRANTED, subId, "tx-1")

        val result = controller.redeem(auth, IapMutation, input())

        assertEquals(IapRedemptionStatus.GRANTED, result.status)
        assertEquals(subId, result.subscriptionId)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { iapEntitlementService.redeem(any(), null) }
    }

    @Test
    fun `redeem forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { iapEntitlementService.redeem(any(), principalId) } returns
            IapRedemptionResult(IapRedemptionStatus.ALREADY_REDEEMED, subId, "tx-1")

        controller.redeem(auth, IapMutation, input())

        coVerify(exactly = 1) { iapEntitlementService.redeem(any(), principalId) }
    }
}
