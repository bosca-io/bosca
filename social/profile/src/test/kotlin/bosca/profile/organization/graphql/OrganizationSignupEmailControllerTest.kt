package bosca.profile.organization.graphql

import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationSignupEmailControllerTest {

    private lateinit var securityService: SecurityService
    private lateinit var controller: OrganizationSignupEmailController

    @BeforeTest
    fun setup() {
        securityService = mockk(relaxed = true)
        controller = OrganizationSignupEmailController(securityService)
    }

    @Test
    fun `email resolves correctly`() = runTest {
        val email = OrganizationSignupEmail("test@example.com", UUID.random(), UUID.random(), OffsetDateTime.now(), OffsetDateTime.now())
        assertEquals("test@example.com", controller.email(email))
    }
}
