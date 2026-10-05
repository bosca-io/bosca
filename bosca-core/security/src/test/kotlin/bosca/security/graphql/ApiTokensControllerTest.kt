package bosca.security.graphql

import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.service.ApiTokenCreationResult
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenScope
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiTokensControllerTest {

    private val apiTokenService = mockk<ApiTokenService>()
    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = GroupEvaluator(securityService)

    private val queryController = ApiTokensController(apiTokenService, groupEvaluator)
    private val mutationController = ApiTokensMutationController(apiTokenService, groupEvaluator)

    private val principalId = UUID.random()
    private val adminGroup = Group(id = UUID.random(), name = "administrators", description = "Admins", type = GroupType.SYSTEM)
    private val editorsGroup = Group(id = UUID.random(), name = "editors", description = "Editors", type = GroupType.PRINCIPAL)

    @Test
    fun `API token callers cannot mutate authentication credentials`() = runTest {
        coEvery { apiTokenService.getTokenById(1) } returns createApiTokenCredential(1, principal = UUID.random())
        for (scopes in listOf(listOf("content:view"), listOf("security:manage"), null)) {
            val auth = mockk<AuthenticationContext> {
                every { principal() } returns ScopedAuthenticatedPrincipal(
                    Principal(id = principalId, anonymous = false), listOf(adminGroup), scopes, null, 1L,
                )
            }
            assertFailsWith<SecurityException> { mutationController.create(auth, ApiTokenInput("token")) }
            assertFailsWith<SecurityException> { mutationController.createForPrincipal(auth, principalId, ApiTokenInput("token")) }
            assertFailsWith<SecurityException> { mutationController.edit(auth, 1, null, null, listOf("security:manage")) }
            assertFailsWith<SecurityException> { mutationController.edit(auth, 2, "name", null, null) }
            assertFailsWith<SecurityException> { mutationController.revoke(auth, 1) }
            assertFailsWith<SecurityException> { mutationController.revokeAll(auth) }
            assertFailsWith<SecurityException> { mutationController.revokeAllForPrincipal(auth, UUID.random()) }
            assertFailsWith<SecurityException> { mutationController.delete(auth, 1) }
        }
        coVerify(exactly = 0) { apiTokenService.editToken(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { apiTokenService.createToken(any(), any(), any()) }
        coVerify(exactly = 0) { apiTokenService.revokeToken(any(), any()) }
        coVerify(exactly = 0) { apiTokenService.deleteToken(any(), any()) }
    }

    @Test
    fun `API token callers can revoke and delete a token owned by the same account`() = runTest {
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns ScopedAuthenticatedPrincipal(
                Principal(id = principalId, anonymous = false), listOf(editorsGroup), listOf("content:view"), null, 1L,
            )
        }
        coEvery { apiTokenService.getTokenById(7) } returns createApiTokenCredential(7)
        coEvery { apiTokenService.getTokenById(8) } returns null
        coEvery { apiTokenService.revokeToken(7, principalId) } returns Unit
        coEvery { apiTokenService.deleteToken(7, principalId) } returns Unit

        assertEquals(true, mutationController.revoke(auth, 7))
        assertEquals(true, mutationController.delete(auth, 7))
        assertFailsWith<SecurityException> { mutationController.revoke(auth, 8) }
        assertFailsWith<SecurityException> { mutationController.delete(auth, 8) }

        coVerify(exactly = 1) { apiTokenService.revokeToken(7, principalId) }
        coVerify(exactly = 1) { apiTokenService.deleteToken(7, principalId) }
    }

    // --- Query: my ---

    @Test
    fun `my returns tokens for authenticated principal`() = runTest {
        val auth = authenticatedContext(principalId)
        val credentials = listOf(createApiTokenCredential(1L), createApiTokenCredential(2L))

        coEvery { apiTokenService.getTokensForPrincipal(principalId) } returns credentials

        val result = queryController.my(auth, limit = 10, offset = 0)
        assertEquals(2, result.size)
    }

    @Test
    fun `my respects pagination`() = runTest {
        val auth = authenticatedContext(principalId)
        val credentials = (1L..5L).map { createApiTokenCredential(it) }

        coEvery { apiTokenService.getTokensForPrincipal(principalId) } returns credentials

        val result = queryController.my(auth, limit = 2, offset = 1)
        assertEquals(2, result.size)
    }

    @Test
    fun `my fails for unauthenticated request`() = runTest {
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null

        assertFailsWith<SecurityException> {
            queryController.my(auth, limit = 10, offset = 0)
        }
    }

    // --- Query: forPrincipal ---

    @Test
    fun `forPrincipal requires admin group`() = runTest {
        val auth = authenticatedContext(principalId, listOf(editorsGroup))

        assertFailsWith<SecurityException> {
            queryController.forPrincipal(auth, UUID.random(), limit = 10, offset = 0)
        }
    }

    @Test
    fun `forPrincipal succeeds for admin`() = runTest {
        val auth = authenticatedContext(principalId, listOf(adminGroup))
        val targetPrincipalId = UUID.random()

        coEvery { apiTokenService.getTokensForPrincipal(targetPrincipalId) } returns emptyList()

        val result = queryController.forPrincipal(auth, targetPrincipalId, limit = 10, offset = 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `forPrincipal allows a non-admin to list their own tokens`() = runTest {
        // Non-admin caller (editors group) requesting their OWN principalId — no admin needed.
        val auth = authenticatedContext(principalId, listOf(editorsGroup))
        val credentials = listOf(createApiTokenCredential(1L), createApiTokenCredential(2L))

        coEvery { apiTokenService.getTokensForPrincipal(principalId) } returns credentials

        val result = queryController.forPrincipal(auth, principalId, limit = 10, offset = 0)
        assertEquals(2, result.size)
    }

    // --- Query: token ---

    @Test
    fun `token returns own token`() = runTest {
        val auth = authenticatedContext(principalId)
        val credential = createApiTokenCredential(42L)

        coEvery { apiTokenService.getTokenById(42L) } returns credential

        val result = queryController.token(auth, 42L)
        assertNotNull(result)
    }

    @Test
    fun `token returns null for non-existent token`() = runTest {
        val auth = authenticatedContext(principalId)

        coEvery { apiTokenService.getTokenById(999L) } returns null

        val result = queryController.token(auth, 999L)
        assertNull(result)
    }

    @Test
    fun `token requires admin to view another principals token`() = runTest {
        val auth = authenticatedContext(principalId, listOf(editorsGroup))
        val otherPrincipalId = UUID.random()
        val credential = createApiTokenCredential(42L, principal = otherPrincipalId)

        coEvery { apiTokenService.getTokenById(42L) } returns credential

        assertFailsWith<SecurityException> {
            queryController.token(auth, 42L)
        }
    }

    // --- Query: availableScopes ---

    @Test
    fun `availableScopes returns all scopes`() {
        val auth = authenticatedContext(principalId)
        val scopes = listOf(ApiTokenScope("content:view", "Read content"))
        every { apiTokenService.availableScopes() } returns scopes

        val result = queryController.availableScopes(auth)
        assertEquals(1, result.size)
        assertEquals("content:view", result[0].name)
    }

    // --- Mutation: create ---

    @Test
    fun `create creates token for authenticated principal`() = runTest {
        val auth = authenticatedContext(principalId)
        val creationResult = ApiTokenCreationResult(
            credential = createApiTokenCredential(1L),
            rawToken = "bsk_a1b2c3d4_testSecret123456789012345678",
        )

        coEvery { apiTokenService.createToken(principalId, any(), principalId) } returns creationResult

        val result = mutationController.create(auth, ApiTokenInput(name = "New Token"))
        assertEquals("bsk_a1b2c3d4_testSecret123456789012345678", result.rawToken)
    }

    @Test
    fun `create rejects unauthenticated and API token callers`() = runTest {
        val unauthenticated = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        assertFailsWith<SecurityException> {
            mutationController.create(unauthenticated, ApiTokenInput(name = "Token"))
        }

        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val scoped = ScopedAuthenticatedPrincipal(
            principal,
            listOf(editorsGroup),
            scopes = null,
            allowedGroupIds = null,
            credentialId = 9,
        )
        val tokenAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns scoped
        }
        assertFailsWith<SecurityException> {
            mutationController.create(tokenAuthentication, ApiTokenInput(name = "Token"))
        }
        assertFailsWith<SecurityException> {
            mutationController.createForPrincipal(tokenAuthentication, principalId, ApiTokenInput(name = "Token"))
        }
    }

    // --- Mutation: createForPrincipal ---

    @Test
    fun `createForPrincipal requires admin`() = runTest {
        val auth = authenticatedContext(principalId, listOf(editorsGroup))

        assertFailsWith<SecurityException> {
            mutationController.createForPrincipal(auth, UUID.random(), ApiTokenInput(name = "Token"))
        }
    }

    @Test
    fun `createForPrincipal allows a non-admin to create their own token`() = runTest {
        // Non-admin caller minting a token for their OWN principalId — equivalent to create.
        val auth = authenticatedContext(principalId, listOf(editorsGroup))
        val creationResult = ApiTokenCreationResult(
            credential = createApiTokenCredential(1L),
            rawToken = "bsk_a1b2c3d4_testSecret123456789012345678",
        )

        coEvery { apiTokenService.createToken(principalId, any(), principalId) } returns creationResult

        val result = mutationController.createForPrincipal(auth, principalId, ApiTokenInput(name = "Token"))
        assertEquals("bsk_a1b2c3d4_testSecret123456789012345678", result.rawToken)
    }

    @Test
    fun `createForPrincipal succeeds for an admin creating for another principal`() = runTest {
        val targetId = UUID.random()
        val auth = authenticatedContext(principalId, listOf(adminGroup))
        val creationResult = ApiTokenCreationResult(
            credential = createApiTokenCredential(1L, targetId),
            rawToken = "bsk_secret",
        )
        coEvery { apiTokenService.createToken(targetId, any(), principalId) } returns creationResult

        assertEquals(
            "bsk_secret",
            mutationController.createForPrincipal(auth, targetId, ApiTokenInput(name = "Token")).rawToken,
        )
    }

    // --- Mutation: revoke ---

    @Test
    fun `revoke calls service with correct principal`() = runTest {
        val auth = authenticatedContext(principalId)

        coEvery { apiTokenService.revokeToken(42L, principalId) } returns Unit

        val result = mutationController.revoke(auth, 42L)
        assertEquals(true, result)
        coVerify { apiTokenService.revokeToken(42L, principalId) }
    }

    @Test
    fun `token mutations reject unauthenticated requests`() = runTest {
        val auth = mockk<AuthenticationContext> {
            every { principal() } returns null
        }

        assertFailsWith<SecurityException> { queryController.forPrincipal(auth, principalId, 10, 0) }
        assertFailsWith<SecurityException> { queryController.token(auth, 1) }
        assertFailsWith<SecurityException> { queryController.availableScopes(auth) }
        assertFailsWith<SecurityException> { mutationController.createForPrincipal(auth, principalId, ApiTokenInput("t")) }
        assertFailsWith<SecurityException> { mutationController.revoke(auth, 1) }
        assertFailsWith<SecurityException> { mutationController.revokeAll(auth) }
        assertFailsWith<SecurityException> { mutationController.revokeAllForPrincipal(auth, principalId) }
        assertFailsWith<SecurityException> { mutationController.edit(auth, 1, null, null, null) }
        assertFailsWith<SecurityException> { mutationController.delete(auth, 1) }
    }

    // --- Mutation: revokeAll ---

    @Test
    fun `revokeAll calls service for current principal`() = runTest {
        val auth = authenticatedContext(principalId)

        coEvery { apiTokenService.revokeAllTokens(principalId) } returns 3

        val result = mutationController.revokeAll(auth)
        assertEquals(true, result)
    }

    // --- Mutation: revokeAllForPrincipal ---

    @Test
    fun `revokeAllForPrincipal requires admin`() = runTest {
        val auth = authenticatedContext(principalId, listOf(editorsGroup))

        assertFailsWith<SecurityException> {
            mutationController.revokeAllForPrincipal(auth, UUID.random())
        }
    }

    @Test
    fun `revokeAllForPrincipal allows a non-admin to revoke their own tokens`() = runTest {
        // Non-admin caller revoking all of their OWN tokens — equivalent to revokeAll.
        val auth = authenticatedContext(principalId, listOf(editorsGroup))

        coEvery { apiTokenService.revokeAllTokens(principalId) } returns 2

        val result = mutationController.revokeAllForPrincipal(auth, principalId)
        assertEquals(true, result)
    }

    @Test
    fun `revokeAllForPrincipal allows admin to revoke another principals tokens`() = runTest {
        val targetId = UUID.random()
        val auth = authenticatedContext(principalId, listOf(adminGroup))
        coEvery { apiTokenService.revokeAllTokens(targetId) } returns 2

        assertEquals(true, mutationController.revokeAllForPrincipal(auth, targetId))
    }

    @Test
    fun `edit and delete delegate using the current principal`() = runTest {
        val auth = authenticatedContext(principalId)
        val updated = createApiTokenCredential(42, name = "Updated")
        coEvery {
            apiTokenService.editToken(42, "Updated", "Description", listOf("content:view"), principalId)
        } returns updated
        coEvery { apiTokenService.deleteToken(42, principalId) } returns Unit

        val token = mutationController.edit(
            auth,
            42,
            "Updated",
            "Description",
            listOf("content:view"),
        )
        assertEquals("Updated", token.attrs.name)
        assertEquals(true, mutationController.delete(auth, 42))
    }

    // --- Helpers ---

    private fun authenticatedContext(
        principalId: UUID,
        groups: List<Group> = listOf(editorsGroup),
    ): AuthenticationContext {
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, groups)
        val context = mockk<AuthenticationContext>()
        every { context.principal() } returns authenticatedPrincipal
        return context
    }

    private fun createApiTokenCredential(
        id: Long,
        principal: UUID = principalId,
        name: String = "Test Token",
    ): PrincipalCredential {
        val attrs = ApiTokenCredentialAttributes(
            identifier = "sha256:mockhash$id",
            name = name,
            tokenPrefix = "bsk_a1b2c3d4",
            createdBy = principal.toString(),
        )
        return PrincipalCredential(principal = principal, attributes = attrs).copy(id = id)
    }
}
