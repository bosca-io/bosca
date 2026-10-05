package bosca.profile.security

import bosca.profile.organization.service.OrganizationService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrganizationPermissionEvaluatorTest {

    private val organizationService = mockk<OrganizationService>()
    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    @Test
    fun `constructor creates evaluator with correct dependencies`() {
        val evaluator = OrganizationPermissionEvaluator(
            organizationService,
            securityService,
            groupEvaluator
        )

        assertNotNull(evaluator)
    }

    @Test
    fun `service property returns organization service`() {
        val evaluator = OrganizationPermissionEvaluator(
            organizationService,
            securityService,
            groupEvaluator
        )

        assertSame(organizationService, evaluator.service)
    }

    @Test
    fun `evaluator extends PermissionEvaluator`() {
        val evaluator = OrganizationPermissionEvaluator(
            organizationService,
            securityService,
            groupEvaluator
        )

        assertTrue(evaluator is bosca.security.service.PermissionEvaluator<*, *>)
    }
}
