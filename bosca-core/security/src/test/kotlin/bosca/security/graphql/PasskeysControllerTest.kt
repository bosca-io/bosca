package bosca.security.graphql

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.HashedEncodedPassword
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.model.CredentialType
import bosca.security.model.Group
import bosca.security.model.GroupType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PasskeysControllerTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = PasskeysController(securityService, groupEvaluator)

    @Test
    fun `current principal passkeys are mapped to public information`() = runTest {
        val principal = Principal(id = UUID.random())
        val authenticated = mockk<AuthenticatedPrincipal> {
            every { asPrincipal() } returns principal
        }
        val attributes = PasskeyCredentialAttributes(
            identifier = "credential-id",
            name = "Laptop",
            publicKeyCose = "public-key",
            transports = listOf("internal"),
            createdAt = "2026-07-25T00:00:00Z",
            lastUsedAt = "2026-07-26T00:00:00Z",
        )
        every { authentication.principal() } returns authenticated
        coEvery {
            securityService.getCredentials(principal, bosca.security.model.CredentialType.PASSKEY)
        } returns listOf(PrincipalCredential(principal = principal.id, attributes = attributes))

        val passkey = controller.current(authentication).single()

        assertEquals(attributes.identifier, passkey.credentialId)
        assertEquals(attributes.name, passkey.name)
        assertEquals(attributes.createdAt, passkey.createdAt)
        assertEquals(attributes.lastUsedAt, passkey.lastUsedAt)
        assertEquals(attributes.transports, passkey.transports)
    }

    @Test
    fun `current rejects an anonymous request`() = runTest {
        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> {
            controller.current(authentication)
        }
    }

    @Test
    fun `admin can list another principal passkeys`() = runTest {
        val principal = Principal(id = UUID.random())
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery {
            securityService.getCredentials(principal, bosca.security.model.CredentialType.PASSKEY)
        } returns emptyList()

        assertEquals(emptyList(), controller.all(authentication, principal.id))
    }

    @Test
    fun `admin passkey lookup rejects a missing principal`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(any()) } returns null

        assertFailsWith<SecurityException> {
            controller.all(authentication, UUID.random())
        }
    }

    @Test
    fun `passkey credential with inconsistent attributes fails loudly`() = runTest {
        val principal = Principal(id = UUID.random())
        val authenticated = mockk<AuthenticatedPrincipal> {
            every { asPrincipal() } returns principal
        }
        val passwordAttributes = CredentialPasswordAttributes(
            "person@example.com",
            object : HashedEncodedPassword {
                override val hash: String = "hash"
            },
        )
        val inconsistent = mockk<PrincipalCredential> {
            every { id } returns 1L
            every { attributes } returns passwordAttributes
        }
        every { authentication.principal() } returns authenticated
        coEvery {
            securityService.getCredentials(principal, bosca.security.model.CredentialType.PASSKEY)
        } returns listOf(inconsistent)

        assertFailsWith<IllegalStateException> {
            controller.current(authentication)
        }
    }

    @Test
    fun `delete passkey delegates for the authenticated principal`() = runTest {
        val principal = Principal(id = UUID.random())
        every { authentication.principal() } returns AuthenticatedPrincipal(principal, emptyList())
        coEvery {
            securityService.deleteCredential(principal.id, CredentialType.PASSKEY, "credential-id")
        } returns Unit

        assertEquals(true, PasskeysMutationController(securityService).delete(authentication, "credential-id"))
        coVerify {
            securityService.deleteCredential(principal.id, CredentialType.PASSKEY, "credential-id")
        }
    }

    @Test
    fun `delete passkey rejects API tokens regardless of scopes or administrator membership`() = runTest {
        val principal = Principal(id = UUID.random(), anonymous = false)
        val adminGroup = Group(
            id = UUID.random(),
            name = "administrators",
            description = "Admins",
            type = GroupType.SYSTEM,
        )
        coEvery { securityService.deleteCredential(any(), any(), any()) } returns Unit

        for (groups in listOf(emptyList(), listOf(adminGroup))) {
            for (scopes in listOf(emptyList(), listOf("content:view"), listOf("security:manage"), null)) {
                every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
                    principal = principal,
                    allGroups = groups,
                    scopes = scopes,
                    allowedGroupIds = null,
                    credentialId = 1L,
                )

                assertFailsWith<SecurityException>("API token scopes=$scopes, groups=$groups") {
                    PasskeysMutationController(securityService).delete(authentication, "credential-id")
                }
            }
        }
        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
    }

    @Test
    fun `delete passkey rejects an anonymous request`() = runTest {
        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> {
            PasskeysMutationController(securityService).delete(authentication, "credential-id")
        }
    }
}
