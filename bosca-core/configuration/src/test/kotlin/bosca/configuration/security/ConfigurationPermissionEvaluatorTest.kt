package bosca.configuration.security

import bosca.configuration.graphql.ConfigurationController
import bosca.configuration.graphql.ConfigurationsController
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationPermission
import bosca.configuration.service.ConfigurationService
import bosca.graphql.Batch
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationPermissionEvaluatorTest {
    private val security = mockk<SecurityService>()
    private val service = mockk<ConfigurationService>()
    private val evaluator = ConfigurationPermissionEvaluator(security, GroupEvaluator(security), service)
    private val administrator = Group(UUID.random(), "administrators", "", GroupType.PRINCIPAL)
    private val team = administrator.copy(id = UUID.random(), name = "configuration-operators")
    private val configuration = Configuration(UUID.random(), "bridge.binding.example.token", "Bot token", false)
    private val secret = buildJsonObject { put("token", "private-test-token") }
    private var permissions: List<EntityPermission> = emptyList()

    @BeforeTest
    fun setup() {
        coEvery { service.getPermissions(any()) } answers { permissions }
        coEvery { service.isParentAllowed(any(), any(), any()) } returns false
        coEvery { service.addPermissionsToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<EntityPermission>>>().setData(configuration.id, permissions)
        }
        coEvery { service.getAll() } returns listOf(configuration)
        coEvery { service.getByKey(configuration.key) } returns configuration
        coEvery { service.getValue(configuration.id) } returns secret
    }

    private fun authentication(scopes: List<String>?, group: Group = administrator): AuthenticationContext =
        context(ScopedAuthenticatedPrincipal(Principal(id = UUID.random(), anonymous = false), listOf(group), scopes, null, 1L))

    private fun context(principal: AuthenticatedPrincipal): AuthenticationContext = mockk {
        every { principal() } returns principal
    }

    @Test
    fun `content view token cannot enumerate or decrypt private configurations`() = runTest {
        val authentication = authentication(listOf(ApiTokenScopes.CONTENT_VIEW.name))
        val queries = ConfigurationsController(service, evaluator)
        assertEquals(emptyList(), queries.all(authentication))
        assertFailsWith<SecurityException> { queries.configuration(authentication, configuration.key) }
        assertFailsWith<SecurityException> { ConfigurationController(service, evaluator).value(authentication, configuration) }
        coVerify(exactly = 0) { service.getValue(any()) }
    }

    @Test
    fun `other scopes cannot grant private configuration access through built in roles`() = runTest {
        val scopes = ApiTokenScopes.all.filter { it != ApiTokenScopes.SECURITY_MANAGE }.map { it.name }
        for (role in listOf("administrators", "sa", "editors", "managers")) {
            val authentication = authentication(scopes, administrator.copy(name = role))
            for (action in PermissionAction.entries) {
                assertFalse(evaluator.isAllowed(authentication, configuration, action), "$role $action")
            }
            assertEquals(emptyList(), evaluator.filterAllowed(authentication, listOf(configuration), PermissionAction.VIEW))
        }
    }

    @Test
    fun `explicit and inherited grants cannot bypass the management scope`() = runTest {
        val authentication = authentication(listOf(ApiTokenScopes.CONTENT_VIEW.name), team)
        permissions = listOf(ConfigurationPermission(configuration.id, PermissionAction.VIEW, team.id))
        assertFalse(evaluator.isAllowed(authentication, configuration, PermissionAction.VIEW))
        permissions = emptyList()
        coEvery { service.isParentAllowed(any(), any(), any()) } returns true
        assertFalse(evaluator.isAllowed(authentication, configuration, PermissionAction.VIEW))
        coVerify(exactly = 0) { service.isParentAllowed(any(), any(), any()) }
    }

    @Test
    fun `management scope retains administrative reads and respects entity grants`() = runTest {
        val administrator = authentication(listOf(ApiTokenScopes.SECURITY_MANAGE.name))
        assertEquals(listOf(configuration), ConfigurationsController(service, evaluator).all(administrator))
        assertEquals(secret, ConfigurationController(service, evaluator).value(administrator, configuration))
        val operator = authentication(listOf(ApiTokenScopes.SECURITY_MANAGE.name), team)
        assertFalse(evaluator.isAllowed(operator, configuration, PermissionAction.VIEW))
        permissions = listOf(ConfigurationPermission(configuration.id, PermissionAction.VIEW, team.id))
        assertTrue(evaluator.isAllowed(operator, configuration, PermissionAction.VIEW))
        assertFalse(evaluator.isAllowed(operator, configuration, PermissionAction.EDIT))
    }

    @Test
    fun `interactive principals and unrestricted tokens retain their entity permissions`() = runTest {
        permissions = listOf(ConfigurationPermission(configuration.id, PermissionAction.VIEW, team.id))
        val interactive = context(AuthenticatedPrincipal(Principal(id = UUID.random()), listOf(team)))
        assertTrue(evaluator.isAllowed(interactive, configuration, PermissionAction.VIEW))
        assertTrue(evaluator.isAllowed(authentication(null, team), configuration, PermissionAction.VIEW))
    }

    @Test
    fun `public configuration reads remain independent of token scopes`() = runTest {
        assertTrue(evaluator.isAllowed(authentication(emptyList(), team), configuration.copy(public = true), PermissionAction.VIEW))
        assertTrue(evaluator.isAllowed(null, configuration.copy(public = true), PermissionAction.VIEW))
        assertFalse(evaluator.isAllowed(authentication(emptyList()), configuration, PermissionAction.VIEW))
    }
}
