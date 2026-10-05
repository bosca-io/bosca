package bosca.profile.organization.graphql

import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationDomainControllerTest {

    private lateinit var securityService: SecurityService
    private lateinit var controller: OrganizationDomainController

    @BeforeTest
    fun setup() {
        securityService = mockk(relaxed = true)
        controller = OrganizationDomainController(securityService)
    }

    @Test
    fun `domain resolves correctly`() = runTest {
        val domain = OrganizationDomain(UUID.random(), "example.com", true, UUID.random())
        assertEquals("example.com", controller.domain(domain))
    }

    @Test
    fun `autoJoin resolves correctly`() = runTest {
        val domain = OrganizationDomain(UUID.random(), "example.com", true, UUID.random())
        assertEquals(true, controller.autoJoin(domain))
    }
}
