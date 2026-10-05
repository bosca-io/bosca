package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.model.Customer
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CustomerService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**cart authorization — owner (customer -> profile) bypasses, everyone else needs ecom admin. */
@OptIn(ExperimentalUuidApi::class)
class CartAccessEvaluatorTest {

    private val profileService = mockk<ProfileService>()
    private val customerService = mockk<CustomerService>()
    private val accountService = mockk<AccountService>()
    private val groups = mockk<GroupEvaluator>()
    private val evaluator = CartAccessEvaluator(profileService, customerService, accountService, groups)

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val customerId = UUID.random()
    private val companyId = UUID.random()

    private fun auth() = ImpersonatedAuthenticationContext(Principal(id = principalId), emptyList())

    private fun cart(customerId: UUID?) = Cart(
        id = UUID.random(), companyId = companyId, storeId = UUID.random(), customerId = customerId,
        expires = OffsetDateTime.now().plusSeconds(3600),
    )

    private fun profile(id: UUID) = mockk<Profile> { every { this@mockk.id } returns id }

    @Test
    fun `owner is allowed without an admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)

        evaluator.verify(auth(), cart(customerId))

        coVerify(exactly = 0) { groups.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `owner may attach an account in their own company`() = runTest {
        val accountId = UUID.random()
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { accountService.get(accountId) } returns Account(id = accountId, companyId = companyId, type = AccountType.CONSUMER)

        evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId, accountId = accountId))

        coVerify(exactly = 0) { groups.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `owner cannot attach an account from another company (cross-tenant credit theft)`() = runTest {
        val foreignAccount = UUID.random()
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { accountService.get(foreignAccount) } returns Account(id = foreignAccount, companyId = UUID.random(), type = AccountType.CONSUMER)

        assertFailsWith<IllegalStateException> {
            evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId, accountId = foreignAccount))
        }
    }

    @Test
    fun `owner cannot attach a non-existent account`() = runTest {
        val ghost = UUID.random()
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { accountService.get(ghost) } returns null

        assertFailsWith<IllegalStateException> {
            evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId, accountId = ghost))
        }
    }

    @Test
    fun `submit-time re-check rejects a cart carrying another company's account`() = runTest {
        val foreignAccount = UUID.random()
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        coEvery { accountService.get(foreignAccount) } returns Account(id = foreignAccount, companyId = UUID.random(), type = AccountType.CONSUMER)

        assertFailsWith<IllegalStateException> {
            evaluator.verify(auth(), cart(customerId).copy(accountId = foreignAccount))
        }
    }

    @Test
    fun `non-owner is denied when not an admin`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = UUID.random())
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        every { groups.verifyHasGroup(any(), any()) } throws IllegalStateException("forbidden")

        assertFailsWith<IllegalStateException> { evaluator.verify(auth(), cart(customerId)) }
    }

    @Test
    fun `admin is allowed on a cart they do not own`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = UUID.random())
        coEvery { profileService.getPrimaryProfile(any()) } returns null
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(auth(), cart(customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `create for another customer requires admin`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = UUID.random())
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)
        every { groups.verifyHasGroup(any(), any()) } throws IllegalStateException("forbidden")

        assertFailsWith<IllegalStateException> {
            evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId))
        }
    }

    // --- verify: cart with no resolvable owner is admin-only (the ownerProfileId == null branch) ---

    @Test
    fun `cart with no customer is admin-only`() = runTest {
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(auth(), cart(customerId = null))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        // The owner lookup short-circuits: no customer => no customerService / profileService calls.
        coVerify(exactly = 0) { customerService.get(any()) }
        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
    }

    @Test
    fun `cart whose customer has no profile falls through to the admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns null
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(auth(), cart(customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        // ownerProfileId is null, so the caller's profile is never resolved.
        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
    }

    @Test
    fun `null authentication on an owned cart falls through to the admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(null, cart(customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        // No principal => the caller's profile is never resolved.
        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
    }

    @Test
    fun `caller with no profile on an owned cart falls through to the admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns null
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(auth(), cart(customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
    }

    // --- verifyCreate owner / admin paths (mirror of verify) ---

    @Test
    fun `create for own customer is allowed without an admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { profileService.getPrimaryProfile(any()) } returns profile(profileId)

        evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId))

        coVerify(exactly = 0) { groups.verifyHasGroup(any(), any()) }
    }

    @Test
    fun `create with no customer is admin-only`() = runTest {
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = null))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        coVerify(exactly = 0) { customerService.get(any()) }
    }

    @Test
    fun `create for a customer with no profile falls through to the admin check`() = runTest {
        coEvery { customerService.get(customerId) } returns null
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verifyCreate(auth(), CartInput(storeId = UUID.random(), customerId = customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
    }

    // --- isOwner: a non-null context whose principal() is null falls through to admin ---

    @Test
    fun `context with a null principal on an owned cart falls through to the admin check`() = runTest {
        val authNoPrincipal = mockk<AuthenticationContext> { every { principal() } returns null }
        coEvery { customerService.get(customerId) } returns Customer(id = customerId, companyId = companyId, profileId = profileId)
        every { groups.verifyHasGroup(any(), any()) } just Runs

        evaluator.verify(authNoPrincipal, cart(customerId))

        coVerify(exactly = 1) { groups.verifyHasGroup(any(), any()) }
        // principal() is null => the caller's profile is never resolved.
        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
    }
}
