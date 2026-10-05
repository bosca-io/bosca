package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.service.CompanyService
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Company field wiring: scalar fields read the source; `organization`/`profile` resolve through the
 * profiles domain; `credits` pages via the company service.
 */
@OptIn(ExperimentalUuidApi::class)
class CompanyControllerTest {

    private val companyService = mockk<CompanyService>()
    private val organizationService = mockk<OrganizationService>()
    private val profileService = mockk<ProfileService>()
    private val controller = CompanyController(companyService, organizationService, profileService)

    private val organizationId = UUID.random()
    private val profileId = UUID.random()
    private val company = Company(
        id = UUID.random(),
        organizationId = organizationId,
        profileId = profileId,
        lengthUnit = LengthUnit.CENTIMETERS,
        weightUnit = WeightUnit.KILOGRAMS,
    )

    @Test
    fun `scalar fields resolve from the source company`() {
        assertEquals(company.id, controller.id(company))
        assertEquals(LengthUnit.CENTIMETERS, controller.lengthUnit(company))
        assertEquals(WeightUnit.KILOGRAMS, controller.weightUnit(company))
        assertEquals(company.created, controller.created(company))
        assertEquals(company.modified, controller.modified(company))
    }

    @Test
    fun `organization resolves through the organization service`() = runTest {
        val organization = mockk<Organization>()
        coEvery { organizationService.getOrganization(organizationId) } returns organization
        assertEquals(organization, controller.organization(company))
    }

    @Test
    fun `profile resolves through the profile service`() = runTest {
        val profile = mockk<Profile>()
        coEvery { profileService.getById(profileId) } returns profile
        assertEquals(profile, controller.profile(company))
    }

    @Test
    fun `credits pages via the company service`() = runTest {
        val credit = CompanyCredit(id = UUID.random(), companyId = company.id, number = "GC-1", balance = Money.of("50.00"))
        coEvery { companyService.getCredits(company.id, 0, 10) } returns listOf(credit)
        assertEquals(listOf(credit), controller.credits(company, 0, 10))
    }
}
