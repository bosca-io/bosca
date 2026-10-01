package bosca.security.graphql

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.ApiToken
import bosca.security.model.ApiTokenCreationResponse
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.DuplicatedAccountIds
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LinkChallenge
import bosca.security.model.LinkProofMethod
import bosca.security.model.LoginResponse
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.model.SignupResult
import bosca.security.model.SimplePasswordAttributes
import bosca.security.model.Token
import bosca.security.service.ApiTokenScope
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.SecurityConfiguration
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimpleControllersTest {

    private val securityService = mockk<SecurityService>()
    private val securityConfiguration = mockk<SecurityConfiguration> { every { oauth2 } returns emptyList() }
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    @Test
    fun `security fields expose their namespaces and permission actions`() {
        val controller = SecurityController(securityService, securityConfiguration)

        assertEquals(PermissionAction.entries.map { it.name }, controller.actions())
        assertSame(ApiTokens, controller.apiTokens())
        assertSame(Groups, controller.groups())
        assertSame(Passkeys, controller.passkeys())
        assertSame(Principals, controller.principals())
        assertSame(AdminQuery, controller.admin())
    }

    @Test
    fun `security principal returns anonymous principal without authentication`() = runTest {
        val result = SecurityController(securityService, securityConfiguration).principal(null)

        assertEquals(UUID.NIL, result.id)
    }

    @Test
    fun `security principal returns authenticated principal`() = runTest {
        val principal = Principal(id = UUID.random())
        every { authentication.principal() } returns AuthenticatedPrincipal(principal, emptyList())

        assertSame(principal, SecurityController(securityService, securityConfiguration).principal(authentication))
    }

    @Test
    fun `security principal falls back to the persisted anonymous principal`() = runTest {
        val principal = Principal(id = UUID.NIL)
        every { authentication.principal() } returns null
        coEvery { securityService.getPrincipalById(UUID.NIL) } returns principal

        assertSame(principal, SecurityController(securityService, securityConfiguration).principal(authentication))
    }

    @Test
    fun `security principal fails when the persisted anonymous principal is missing`() = runTest {
        every { authentication.principal() } returns null
        coEvery { securityService.getPrincipalById(UUID.NIL) } returns null

        assertFailsWith<IllegalStateException> {
            SecurityController(securityService, securityConfiguration).principal(authentication)
        }
    }

    @Test
    fun `permission controllers expose fields and resolve groups`() = runTest {
        val groupId = UUID.random()
        val group = group(groupId)
        val entityPermission = mockk<EntityPermission> {
            every { this@mockk.groupId } returns groupId
            every { action } returns PermissionAction.VIEW
        }
        val permission = Permission(groupId, PermissionAction.EDIT)
        coEvery { securityService.getGroupById(groupId) } returns group

        val entityController = EntityPermissionController(securityService)
        assertEquals(groupId, entityController.groupId(entityPermission))
        assertEquals(PermissionAction.VIEW, entityController.action(entityPermission))
        assertSame(group, entityController.group(entityPermission))

        val controller = PermissionController(securityService)
        assertEquals(groupId, controller.groupId(permission))
        assertEquals(PermissionAction.EDIT, controller.action(permission))
        assertSame(group, controller.group(permission))
    }

    @Test
    fun `group mutations authorize and delegate`() = runTest {
        val added = group(UUID.random())
        val edited = group(UUID.random())
        coEvery { securityService.addGroup(any()) } returns added
        coEvery { securityService.editGroup(any()) } returns edited
        coEvery { securityService.deleteGroup(any()) } returns Unit
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        val controller = GroupsMutationController(securityService, groupEvaluator)

        assertSame(added, controller.addGroup(authentication, "new", "description", GroupType.SYSTEM))
        assertSame(
            edited,
            controller.editGroup(authentication, edited.id, "edited", "new description", GroupType.PRINCIPAL)
        )
        assertTrue(controller.deleteGroup(authentication, edited.id))

        verify(exactly = 3) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { securityService.addGroup(Group(UUID.NIL, "new", "description", GroupType.SYSTEM)) }
        coVerify {
            securityService.editGroup(Group(edited.id, "edited", "new description", GroupType.PRINCIPAL))
        }
        coVerify { securityService.deleteGroup(edited.id) }
    }

    @Test
    fun `group queries authorize and delegate pagination`() = runTest {
        val groups = listOf(group(UUID.random()))
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getGroups(GroupType.SYSTEM, 4, 5) } returns groups
        coEvery { securityService.findGroups("admin", null, 6, 7) } returns groups
        val controller = GroupsController(securityService, groupEvaluator)

        assertSame(groups, controller.all(authentication, GroupType.SYSTEM, 5, 4))
        assertSame(groups, controller.find(authentication, "admin", null, 7, 6))

        verify(exactly = 2) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `login response fields resolve their values`() = runTest {
        val principal = Principal(id = UUID.random())
        val profiles = listOf(mockk<Profile>())
        val profileService = mockk<ProfileService>()
        val response = loginResponse(principal.id)
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery { profileService.getByPrincipal(principal.id) } returns profiles
        val controller = LoginResponseController(securityService, profileService)

        assertSame(principal, controller.principal(response))
        assertSame(profiles, controller.profile(response))
        assertEquals("refresh", controller.refreshToken(response))
        assertSame(response.token, controller.token(response))
        assertTrue(controller.accountCreated(response))
        assertEquals("studio", controller.originator(response))
    }

    @Test
    fun `password login delegates credential attributes`() = runTest {
        val response = loginResponse(UUID.random())
        coEvery { securityService.loginWithCredential(any()) } returns response

        assertSame(response, LoginController(securityService).password("person@example.com", "secret"))

        coVerify {
            securityService.loginWithCredential(
                match {
                    it is SimplePasswordAttributes &&
                        it.identifier == "person@example.com" &&
                        it.password == "secret"
                }
            )
        }
    }

    @Test
    fun `admin duplicate account query authorizes and delegates`() = runTest {
        val duplicates = listOf(DuplicatedAccountIds("person@example.com", listOf(UUID.random())))
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.findDuplicateAccounts() } returns duplicates

        assertSame(
            duplicates,
            AdminQueryController(securityService, groupEvaluator).duplicateAccounts(authentication)
        )
        verify { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `link mutations delegate all proof methods`() = runTest {
        val response = loginResponse(UUID.random())
        val call = mockk<ServerCall> {
            every { request.appOrigin } returns "https://studio.example"
        }
        coEvery { securityService.confirmAccountLinkWithPassword("pending", "secret") } returns response
        coEvery {
            securityService.requestAccountLinkEmailProof("pending", "https://studio.example")
        } returns Unit
        coEvery { securityService.confirmAccountLinkWithEmail("proof") } returns response
        val controller = LinkMutationController(securityService)

        assertSame(response, controller.confirmPassword("pending", "secret"))
        assertTrue(controller.requestEmailProof("pending", call))
        assertSame(response, controller.confirmEmail("proof"))
    }

    @Test
    fun `duplicated account controller exposes email and resolves principals`() = runTest {
        val principalIds = listOf(UUID.random(), UUID.random())
        val principals = principalIds.map { Principal(id = it) }
        val duplicate = DuplicatedAccountIds("person@example.com", principalIds)
        coEvery { securityService.getPrincipalsById(principalIds) } returns principals
        val controller = DuplicatedAccountIdsController(securityService)

        assertEquals("person@example.com", controller.email(duplicate))
        assertSame(principals, controller.principals(duplicate))
    }

    @Test
    fun `signup result and link challenge controllers expose values`() {
        val principal = Principal(id = UUID.random())
        val response = loginResponse(principal.id)
        val challenge = LinkChallenge("pending", listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL))
        val signup = SignupResult(principal, response, challenge)
        val signupController = SignupResultController()
        val challengeController = LinkChallengeController()

        assertSame(principal, signupController.principal(signup))
        assertSame(response, signupController.loginResponse(signup))
        assertSame(challenge, signupController.linkChallenge(signup))
        assertEquals("pending", challengeController.token(challenge))
        assertEquals(listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL), challengeController.methods(challenge))
    }

    @Test
    fun `API token response and scope controllers expose values`() {
        val token = apiToken()
        val response = ApiTokenCreationResponse(token, "bsk_secret")
        val scope = ApiTokenScope("content:view", "Read content")
        val responseController = ApiTokenCreationResponseController()
        val scopeController = ApiTokenScopeInfoController()

        assertSame(token, responseController.apiToken(response))
        assertEquals("bsk_secret", responseController.rawToken(response))
        assertEquals("content:view", scopeController.name(scope))
        assertEquals("Read content", scopeController.description(scope))
    }

    private fun group(id: UUID) = Group(id, "administrators", "Administrators", GroupType.SYSTEM)

    private fun loginResponse(principalId: UUID) = LoginResponse(
        principalId = principalId,
        refreshToken = "refresh",
        token = Token(20, 10, "jwt"),
        accountCreated = true,
        originator = "studio",
    )

    private fun apiToken(): ApiToken {
        val attributes = ApiTokenCredentialAttributes(
            identifier = "hash",
            name = "CI",
            tokenPrefix = "bsk_prefix",
            createdBy = UUID.random().toString(),
        )
        return ApiToken(PrincipalCredential(principal = UUID.random(), attributes = attributes))
    }
}
