package bosca.pipelines.service

import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAuthority
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipelineRunAuthenticationTest {
    private val json = Json
    private val security = mockk<SecurityService>()
    private val tokens = mockk<ApiTokenService>()
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val group = Group(UUID.random(), "pipeline-users", "Pipeline users", GroupType.PRINCIPAL)
    private val addedGroup = Group(UUID.random(), "administrators", "Administrators", GroupType.SYSTEM)
    private val authority = PipelineRunAuthority(false, listOf(group.id), listOf("jobs:execute"), 42)
    private val run = PipelineRun(pipelineId = UUID.random(), graphSnapshot = JsonObject(emptyMap()), principalId = principal.id, authority = authority)
    private var attributes = ApiTokenCredentialAttributes(
        identifier = "test", name = "Test", tokenPrefix = "test", scopes = authority.scopes,
        allowedGroups = listOf(group.id.toString()), createdBy = principal.id.toString(),
    )

    @BeforeTest
    fun setup() {
        coEvery { security.getPrincipalById(principal.id) } returns principal
        coEvery { security.getPrincipalGroups(principal.id) } returns listOf(group, addedGroup)
        coEvery { tokens.getTokenById(42) } answers { PrincipalCredential(principal.id, attributes).copy(id = 42) }
    }

    private suspend fun resolve(value: PipelineRun = run) = resolveRunAuthentication(value, security, tokens, json, "sa")

    @Test
    fun `capture preserves effective groups and credential restrictions`() = runTest {
        val scoped = ScopedAuthenticatedPrincipal(principal, listOf(group, addedGroup), authority.scopes, setOf(group.id), 42)
        val authentication = object : AuthenticationContext(null, null) { override fun principal() = scoped }
        assertEquals(authority, captureRunAuthority(authentication, security))
        val unrestricted = ScopedAuthenticatedPrincipal(principal, listOf(group), null, null, 42)
        val unrestrictedAuth = object : AuthenticationContext(null, null) { override fun principal() = unrestricted }
        assertNull(captureRunAuthority(unrestrictedAuth, security).scopes)
        val ordinary = captureRunAuthority(ImpersonatedAuthenticationContext(principal, listOf(group)), security)
        assertEquals(authority.copy(scopes = null, credentialId = null), ordinary)
        assertEquals(PipelineRunAuthority(false), captureRunAuthority(AuthenticationContext(null, null), security))
        assertEquals(PipelineRunAuthority(true), captureRunAuthority(null, security))
    }

    @Test
    fun `broader current credentials and membership never broaden a captured run`() = runTest {
        attributes = attributes.copy(scopes = null, allowedGroups = null)
        val caller = assertIs<ScopedAuthenticatedPrincipal>(resolve().principal())
        assertEquals(42, caller.credentialId)
        assertTrue(caller.hasScope("jobs:execute"))
        assertFalse(caller.hasScope("security:manage"))
        assertTrue(caller.hasGroup(group.id))
        assertFalse(caller.hasGroup(addedGroup.id))
        attributes = attributes.copy(scopes = listOf("jobs:execute", "security:manage"), allowedGroups = listOf(group.id.toString(), addedGroup.id.toString()))
        assertEquals(caller.scopes, assertIs<ScopedAuthenticatedPrincipal>(resolve().principal()).scopes)
        val ordinary = resolve(run.copy(authority = authority.copy(credentialId = null, scopes = null))).principal()
        assertTrue(ordinary?.hasGroup(group.id) == true)
        assertFalse(ordinary?.hasGroup(addedGroup.id) == true)
        attributes = attributes.copy(scopes = null)
        assertNull(assertIs<ScopedAuthenticatedPrincipal>(resolve(run.copy(authority = authority.copy(scopes = null))).principal()).scopes)
        attributes = attributes.copy(scopes = emptyList())
        assertTrue(assertIs<ScopedAuthenticatedPrincipal>(resolve(run.copy(authority = authority.copy(scopes = emptyList()))).principal()).scopes.orEmpty().isEmpty())
    }

    @Test
    fun `anonymous stays anonymous and only system runs use service account`() = runTest {
        assertNull(resolve(run.copy(principalId = null, authority = PipelineRunAuthority(false))).principal())
        coVerify(exactly = 0) { security.getPrincipalByIdentifier(any()) }
        coEvery { security.getPrincipalByIdentifier("sa") } returns principal
        assertEquals(principal.id, resolve(run.copy(principalId = null, authority = PipelineRunAuthority(true))).principal()?.id)
        coVerify(exactly = 1) { security.getPrincipalByIdentifier("sa") }
    }

    @Test
    fun `stored authority requires an explicit system or caller designation`() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(PipelineRunAuthority.serializer(), "{}")
        }
        assertEquals(PipelineRunAuthority(false), json.decodeFromString(PipelineRunAuthority.serializer(), "{\"system\":false}"))
    }

    @Test
    fun `missing or inconsistent captured authority fails closed`() = runTest {
        val invalid = listOf(
            run.copy(authority = null),
            run.copy(authority = PipelineRunAuthority(true)),
            run.copy(principalId = null, authority = PipelineRunAuthority(true, groupIds = emptyList())),
            run.copy(principalId = null, authority = PipelineRunAuthority(true, scopes = emptyList())),
            run.copy(principalId = null, authority = PipelineRunAuthority(true, credentialId = 42)),
            run.copy(principalId = null),
            run.copy(principalId = null, authority = PipelineRunAuthority(false, scopes = emptyList())),
            run.copy(principalId = null, authority = PipelineRunAuthority(false, credentialId = 42)),
            run.copy(authority = authority.copy(groupIds = null)),
            run.copy(authority = authority.copy(credentialId = null)),
        )
        for (candidate in invalid) assertFailsWith<IllegalStateException> { resolve(candidate) }
    }

    @Test
    fun `deleted or missing principal and lost groups reject execution`() = runTest {
        coEvery { security.getPrincipalById(principal.id) } returns null
        assertFailsWith<IllegalStateException> { resolve() }
        coEvery { security.getPrincipalById(principal.id) } returns principal.copy(deletedAt = OffsetDateTime.now())
        assertFailsWith<IllegalStateException> { resolve() }
        coEvery { security.getPrincipalById(principal.id) } returns principal
        coEvery { security.getPrincipalGroups(principal.id) } returns listOf(addedGroup)
        assertFailsWith<IllegalStateException> { resolve() }
    }

    @Test
    fun `revoked expired missing and foreign credentials reject execution`() = runTest {
        attributes = attributes.copy(revokedAt = OffsetDateTime.now().toString())
        assertFailsWith<IllegalStateException> { resolve() }
        attributes = attributes.copy(revokedAt = null, expiresAt = OffsetDateTime.now().minusSeconds(1).toString())
        assertFailsWith<IllegalStateException> { resolve() }
        attributes = attributes.copy(expiresAt = OffsetDateTime.now().plusHours(1).toString())
        assertEquals(principal.id, resolve().principal()?.id)
        coEvery { tokens.getTokenById(42) } returns null
        assertFailsWith<IllegalStateException> { resolve() }
        coEvery { tokens.getTokenById(42) } returns PrincipalCredential(UUID.random(), attributes).copy(id = 42)
        assertFailsWith<IllegalStateException> { resolve() }
    }

    @Test
    fun `narrowed token groups and scopes reject execution`() = runTest {
        attributes = attributes.copy(allowedGroups = emptyList())
        assertFailsWith<IllegalStateException> { resolve() }
        attributes = attributes.copy(allowedGroups = null, scopes = emptyList())
        assertFailsWith<IllegalStateException> { resolve() }
        assertFailsWith<IllegalStateException> { resolve(run.copy(authority = authority.copy(scopes = null))) }
    }

    @Test
    fun `cancellation and infrastructure failures propagate`() = runTest {
        val cancelled = CancellationException("cancelled")
        coEvery { tokens.getTokenById(42) } throws cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> { resolve() })
        val failure = java.io.IOException("unavailable")
        coEvery { tokens.getTokenById(42) } throws failure
        assertSame(failure, assertFailsWith<java.io.IOException> { resolve() })
    }

    @Test
    fun `capture records a session-bound credential`() = runTest {
        val scoped = ScopedAuthenticatedPrincipal(principal, listOf(group), authority.scopes, setOf(group.id), 42, sessionBoundCredential = true)
        val authentication = object : AuthenticationContext(null, null) { override fun principal() = scoped }
        assertTrue(captureRunAuthority(authentication, security).sessionBoundCredential)
    }

    @Test
    fun `a retired session-bound credential keeps only the captured limits`() = runTest {
        val bound = run.copy(authority = authority.copy(sessionBoundCredential = true))
        coEvery { tokens.getTokenById(42) } returns null
        val caller = assertIs<ScopedAuthenticatedPrincipal>(resolve(bound).principal())
        assertEquals(42, caller.credentialId)
        assertTrue(caller.sessionBoundCredential)
        assertTrue(caller.hasScope("jobs:execute"))
        assertFalse(caller.hasScope("security:manage"))
        assertTrue(caller.hasGroup(group.id))
        assertFalse(caller.hasGroup(addedGroup.id))
        coVerify(exactly = 0) { tokens.getTokenById(any()) }
    }

    @Test
    fun `a session-bound run still ends when the caller loses access`() = runTest {
        val bound = run.copy(authority = authority.copy(sessionBoundCredential = true))
        coEvery { security.getPrincipalGroups(principal.id) } returns listOf(addedGroup)
        assertFailsWith<IllegalStateException> { resolve(bound) }
        coEvery { security.getPrincipalById(principal.id) } returns principal.copy(deletedAt = OffsetDateTime.now())
        assertFailsWith<IllegalStateException> { resolve(bound) }
    }
}
