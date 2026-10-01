package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.CompanyCreditInput
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.service.CompanyService
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
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Mutations scoped to one company (the id carried by [CompanyMutation]). Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class CompanyMutationControllerTest {

    private val companyService = mockk<CompanyService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = CompanyMutationController(companyService, groups)
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    @Test
    fun `addCredit gates on admin then issues via the service`() = runTest {
        val input = CompanyCreditInput(number = "GC-1", balance = Money.of("50.00"))
        val credit = CompanyCredit(id = UUID.random(), companyId = companyId, number = "GC-1", balance = Money.of("50.00"))
        coEvery { companyService.addCredit(companyId, input, null) } returns credit

        val result = controller.addCredit(auth, CompanyMutation(companyId), input)

        assertEquals(credit, result)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { companyService.addCredit(companyId, input, null) }
    }

    @Test
    fun `editCredit gates on admin then edits via the service`() = runTest {
        val creditId = UUID.random()
        val input = CompanyCreditInput(description = "VIP", balance = Money.of("75.00"))
        val updated = CompanyCredit(id = creditId, companyId = companyId, number = "GC-1", description = "VIP", balance = Money.of("75.00"))
        coEvery { companyService.editCredit(creditId, input, null) } returns updated

        val result = controller.editCredit(auth, CompanyMutation(companyId), creditId, input)

        assertEquals(updated, result)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { companyService.editCredit(creditId, input, null) }
    }

    @Test
    fun `editCredit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val creditId = UUID.random()
        val input = CompanyCreditInput(balance = Money.of("75.00"))
        val updated = CompanyCredit(id = creditId, companyId = companyId, number = "GC-1", balance = Money.of("75.00"))
        coEvery { companyService.editCredit(creditId, input, principalId) } returns updated

        controller.editCredit(auth, CompanyMutation(companyId), creditId, input)

        coVerify(exactly = 1) { companyService.editCredit(creditId, input, principalId) }
    }

    @Test
    fun `deleteCredit gates on admin then deletes via the service`() = runTest {
        val creditId = UUID.random()
        coEvery { companyService.deleteCredit(creditId, null) } returns true

        val result = controller.deleteCredit(auth, CompanyMutation(companyId), creditId)

        assertEquals(true, result)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { companyService.deleteCredit(creditId, null) }
    }

    @Test
    fun `deleteCredit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val creditId = UUID.random()
        coEvery { companyService.deleteCredit(creditId, principalId) } returns false

        val result = controller.deleteCredit(auth, CompanyMutation(companyId), creditId)

        assertEquals(false, result)
        coVerify(exactly = 1) { companyService.deleteCredit(creditId, principalId) }
    }

    @Test
    fun `setUnits gates on admin then updates via the service`() = runTest {
        val updated = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random(),
            lengthUnit = LengthUnit.CENTIMETERS, weightUnit = WeightUnit.KILOGRAMS)
        coEvery {
            companyService.setUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS, null)
        } returns updated

        val result = controller.setUnits(auth, CompanyMutation(companyId), LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS)

        assertEquals(updated, result)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { companyService.setUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS, null) }
    }

    @Test
    fun `addCredit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val input = CompanyCreditInput(number = "GC-1", balance = Money.of("50.00"))
        val credit = CompanyCredit(id = UUID.random(), companyId = companyId, number = "GC-1", balance = Money.of("50.00"))
        coEvery { companyService.addCredit(companyId, input, principalId) } returns credit

        controller.addCredit(auth, CompanyMutation(companyId), input)

        coVerify(exactly = 1) { companyService.addCredit(companyId, input, principalId) }
    }

    @Test
    fun `setUnits forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val updated = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random(),
            lengthUnit = LengthUnit.CENTIMETERS, weightUnit = WeightUnit.KILOGRAMS)
        coEvery {
            companyService.setUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS, principalId)
        } returns updated

        controller.setUnits(auth, CompanyMutation(companyId), LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS)

        coVerify(exactly = 1) { companyService.setUnits(companyId, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS, principalId) }
    }

    @Test
    fun `setUnits throws when the company is missing`() = runTest {
        coEvery {
            companyService.setUnits(companyId, LengthUnit.INCHES, WeightUnit.POUNDS, null)
        } returns null

        assertFailsWith<IllegalStateException> {
            controller.setUnits(auth, CompanyMutation(companyId), LengthUnit.INCHES, WeightUnit.POUNDS)
        }
    }
}
