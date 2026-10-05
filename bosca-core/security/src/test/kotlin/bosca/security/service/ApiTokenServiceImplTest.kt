package bosca.security.service

import bosca.pubsub.PubSubService
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.CredentialType
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTokenServiceImplTest {

    private val credentialsRepository = mockk<PrincipalCredentialsRepository>()
    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = GroupEvaluator(securityService)
    private val pubSubService = mockk<PubSubService>().also {
        every { it.subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<String>>()) } returns emptyFlow()
        coEvery { it.publish(any(), any<kotlinx.serialization.SerializationStrategy<String>>(), any()) } returns Unit
    }

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    private val service = ApiTokenServiceImpl(
        credentialsRepository = credentialsRepository,
        securityService = securityService,
        groupEvaluator = groupEvaluator,
        pubSubService = pubSubService,
    )

    private val principalId = UUID.random()
    private val adminGroup = Group(id = UUID.random(), name = "administrators", description = "Admins", type = GroupType.SYSTEM)
    private val editorsGroup = Group(id = UUID.random(), name = "editors", description = "Editors", type = GroupType.PRINCIPAL)

    // --- Token Creation ---

    @Test
    fun `createToken generates bsk-prefixed token with correct format`() = runTest {
        setupForCreate()

        val result = service.createToken(
            principalId = principalId,
            input = ApiTokenInput(name = "Test Token"),
            createdBy = principalId,
        )

        assertTrue(result.rawToken.startsWith("bsk_"), "token should start with bsk_")
        val parts = result.rawToken.split("_")
        assertEquals(3, parts.size, "token should have 3 underscore-separated parts")
        assertEquals("bsk", parts[0])
        assertEquals(8, parts[1].length, "principal short ID should be 8 characters")
        assertTrue(parts[2].length > 20, "secret portion should be at least 20 characters")
    }

    @Test
    fun `createToken stores SHA-256 hash as identifier`() = runTest {
        val credentialSlot = slot<PrincipalCredential>()
        setupForCreate(credentialSlot = credentialSlot)

        val result = service.createToken(
            principalId = principalId,
            input = ApiTokenInput(name = "Test Token"),
            createdBy = principalId,
        )

        val storedAttrs = credentialSlot.captured.attributes as ApiTokenCredentialAttributes
        val expectedHash = ApiTokenServiceImpl.sha256Hex(result.rawToken)
        assertEquals("sha256:$expectedHash", storedAttrs.identifier)
    }

    @Test
    fun `createToken stores token prefix for UI display`() = runTest {
        val credentialSlot = slot<PrincipalCredential>()
        setupForCreate(credentialSlot = credentialSlot)

        val result = service.createToken(
            principalId = principalId,
            input = ApiTokenInput(name = "Test Token"),
            createdBy = principalId,
        )

        val storedAttrs = credentialSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals(result.rawToken.take(12), storedAttrs.tokenPrefix)
    }

    @Test
    fun `createToken preserves scopes and allowed groups`() = runTest {
        val credentialSlot = slot<PrincipalCredential>()
        coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN) } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(adminGroup, editorsGroup)
        coEvery { credentialsRepository.add(capture(credentialSlot)) } answers { credentialSlot.captured.copy(id = 1L) }

        val scopes = listOf("content:edit", "security:manage")
        val allowedGroups = listOf(editorsGroup.id)

        service.createToken(
            principalId = principalId,
            input = ApiTokenInput(
                name = "Scoped Token",
                scopes = scopes,
                allowedGroups = allowedGroups,
            ),
            createdBy = principalId,
        )

        val storedAttrs = credentialSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals(scopes, storedAttrs.scopes)
        assertEquals(listOf(editorsGroup.id.toString()), storedAttrs.allowedGroups)
    }

    @Test
    fun `createToken rejects invalid scopes`() = runTest {
        setupForCreate()

        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId = principalId,
                input = ApiTokenInput(name = "Bad Token", scopes = listOf("invalid:scope")),
                createdBy = principalId,
            )
        }
    }

    @Test
    fun `createToken rejects groups not assigned to principal`() = runTest {
        coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN) } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)

        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId = principalId,
                input = ApiTokenInput(name = "Bad Token", allowedGroups = listOf(adminGroup.id)),
                createdBy = principalId,
            )
        }
    }

    @Test
    fun `createToken enforces maximum token limit`() = runTest {
        val existingTokens = (1..50).map { createMockCredential(it.toLong()) }
        coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN) } returns existingTokens

        assertFailsWith<SecurityException> {
            service.createToken(
                principalId = principalId,
                input = ApiTokenInput(name = "One Too Many"),
                createdBy = principalId,
            )
        }
    }

    @Test
    fun `createToken generates unique tokens on successive calls`() = runTest {
        setupForCreate()

        val result1 = service.createToken(principalId, ApiTokenInput(name = "Token 1"), principalId)
        val result2 = service.createToken(principalId, ApiTokenInput(name = "Token 2"), principalId)

        assertTrue(result1.rawToken != result2.rawToken, "successive tokens should be unique")
    }

    @Test
    fun `createEphemeralToken requires an expiration`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createEphemeralToken(
                principalId,
                ApiTokenInput(name = "CI workload"),
                principalId,
            )
        }
    }

    @Test
    fun `createEphemeralToken bypasses personal grants and quota`() = runTest {
        val credentialSlot = slot<PrincipalCredential>()
        coEvery {
            credentialsRepository.add(capture(credentialSlot))
        } answers {
            credentialSlot.captured.copy(id = 1L)
        }

        val result = service.createEphemeralToken(
            principalId = principalId,
            input = ApiTokenInput(
                name = "CI workload",
                scopes = listOf("ci:execute", "storage:write"),
                expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).toString(),
            ),
            createdBy = principalId,
        )

        assertTrue(result.rawToken.startsWith("bsk_"))
        val attributes = credentialSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals(listOf("ci:execute", "storage:write"), attributes.scopes)
        assertNotNull(attributes.expiresAt)
        coVerify(exactly = 0) {
            credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        }
        coVerify(exactly = 0) { securityService.getPrincipalGroups(principalId) }
    }

    // --- Token Authentication ---

    @Test
    fun `authenticate succeeds with valid token`() = runTest {
        val rawToken = "bsk_a1b2c3d4_testSecret123456789012345678"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            name = "Test Token",
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit

        val result = service.authenticate(rawToken, "192.168.1.1")

        assertEquals(principalId, result.id)
        assertTrue(result.hasGroup("editors"))
        assertEquals(1L, result.credentialId)
    }

    @Test
    fun `authenticate fails with unknown token`() = runTest {
        val rawToken = "bsk_a1b2c3d4_unknownToken123456789012345"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns emptyList()

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `authenticate fails with revoked token`() = runTest {
        val rawToken = "bsk_a1b2c3d4_revokedToken12345678901234"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            revokedAt = OffsetDateTime.now().toString(),
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `authenticate fails with expired token`() = runTest {
        val rawToken = "bsk_a1b2c3d4_expiredToken12345678901234"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `authenticate fails with non-bsk token`() = runTest {
        assertFailsWith<SecurityException> {
            service.authenticate("not_a_valid_token", null)
        }
    }

    @Test
    fun `authenticate fails for anonymous principal`() = runTest {
        val rawToken = "bsk_a1b2c3d4_anonToken1234567890123456"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val anonPrincipal = Principal(id = principalId, anonymous = true)
        val credential = createMockCredential(id = 1L, identifier = "sha256:$hash")

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns anonPrincipal

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `authenticate applies group restrictions`() = runTest {
        val rawToken = "bsk_a1b2c3d4_groupRestricted12345678901"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            allowedGroups = listOf(editorsGroup.id.toString()),
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(adminGroup, editorsGroup)
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit

        val result = service.authenticate(rawToken, null)

        assertTrue(result.hasGroup("editors"))
        assertFalse(result.hasGroup("administrators"))
    }

    @Test
    fun `authenticate carries scope restrictions`() = runTest {
        val rawToken = "bsk_a1b2c3d4_scopeRestricted1234567890"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val scopes = listOf("content:view", "storage:read")
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            scopes = scopes,
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit

        val result = service.authenticate(rawToken, null)

        assertTrue(result.hasScope("content:view"))
        assertTrue(result.hasScope("storage:read"))
        assertFalse(result.hasScope("content:edit"))
    }

    @Test
    fun `authenticate updates last used fields`() = runTest {
        val rawToken = "bsk_a1b2c3d4_lastUsedUpdate12345678901"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        val credential = createMockCredential(id = 1L, identifier = "sha256:$hash")
        val lastUsedAtSlot = slot<String>()
        val lastUsedIpSlot = slot<String?>()

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        coEvery { credentialsRepository.updateLastUsed(1L, capture(lastUsedAtSlot), captureNullable(lastUsedIpSlot)) } returns Unit

        service.authenticate(rawToken, "10.0.0.1")

        assertTrue(lastUsedAtSlot.isCaptured)
        assertNotNull(lastUsedAtSlot.captured)
        assertEquals("10.0.0.1", lastUsedIpSlot.captured)
    }

    // --- Token Revocation ---

    @Test
    fun `revokeToken sets revoked_at on the credential`() = runTest {
        val credential = createMockCredential(id = 1L)
        val updatedSlot = slot<PrincipalCredential>()

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { credentialsRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }

        service.revokeToken(1L, principalId)

        val updatedAttrs = updatedSlot.captured.attributes as ApiTokenCredentialAttributes
        assertNotNull(updatedAttrs.revokedAt)
    }

    @Test
    fun `revokeToken is idempotent for already revoked tokens`() = runTest {
        val credential = createMockCredential(id = 1L, revokedAt = OffsetDateTime.now().toString())

        coEvery { credentialsRepository.getById(1L) } returns credential

        // Should not throw, should not update
        service.revokeToken(1L, principalId)

        coVerify(exactly = 0) { credentialsRepository.update(any()) }
    }

    @Test
    fun `revokeToken fails for non-owner non-admin`() = runTest {
        val otherPrincipalId = UUID.random()
        val otherPrincipal = Principal(id = otherPrincipalId, anonymous = false, verified = true)
        val credential = createMockCredential(id = 1L)

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { securityService.getPrincipalById(otherPrincipalId) } returns otherPrincipal
        coEvery { securityService.getPrincipalGroups(otherPrincipalId) } returns listOf(editorsGroup)

        assertFailsWith<SecurityException> {
            service.revokeToken(1L, otherPrincipalId)
        }
    }

    @Test
    fun `revokeToken succeeds for admin revoking another principals token`() = runTest {
        val adminPrincipalId = UUID.random()
        val adminPrincipal = Principal(id = adminPrincipalId, anonymous = false, verified = true)
        val credential = createMockCredential(id = 1L)

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { securityService.getPrincipalById(adminPrincipalId) } returns adminPrincipal
        coEvery { securityService.getPrincipalGroups(adminPrincipalId) } returns listOf(adminGroup)
        coEvery { credentialsRepository.update(any()) } answers { firstArg() }

        service.revokeToken(1L, adminPrincipalId)

        coVerify { credentialsRepository.update(any()) }
    }

    // --- Revoke All ---

    @Test
    fun `revokeAllTokens revokes only active tokens`() = runTest {
        val active1 = createMockCredential(id = 1L)
        val active2 = createMockCredential(id = 2L)
        val alreadyRevoked = createMockCredential(id = 3L, revokedAt = OffsetDateTime.now().toString())

        coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN) } returns
            listOf(active1, active2, alreadyRevoked)
        coEvery { credentialsRepository.update(any()) } answers { firstArg() }

        val count = service.revokeAllTokens(principalId)

        assertEquals(2, count)
        coVerify(exactly = 2) { credentialsRepository.update(any()) }
    }

    // --- Delete ---

    @Test
    fun `deleteToken fails for non-revoked token`() = runTest {
        val credential = createMockCredential(id = 1L, revokedAt = null)

        coEvery { credentialsRepository.getById(1L) } returns credential

        assertFailsWith<SecurityException> {
            service.deleteToken(1L, principalId)
        }
    }

    @Test
    fun `deleteToken succeeds for revoked token`() = runTest {
        val credential = createMockCredential(id = 1L, revokedAt = OffsetDateTime.now().toString())

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { credentialsRepository.delete(principalId, CredentialType.API_TOKEN, any()) } returns Unit

        service.deleteToken(1L, principalId)

        coVerify { credentialsRepository.delete(principalId, CredentialType.API_TOKEN, any()) }
    }

    // --- Edit ---

    @Test
    fun `editToken updates name and description`() = runTest {
        val credential = createMockCredential(id = 1L, name = "Old Name")
        val updatedSlot = slot<PrincipalCredential>()

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { credentialsRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }

        service.editToken(1L, "New Name", "New Description", null, principalId)

        val updatedAttrs = updatedSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals("New Name", updatedAttrs.name)
        assertEquals("New Description", updatedAttrs.description)
    }

    @Test
    fun `editToken preserves name when null passed`() = runTest {
        val credential = createMockCredential(id = 1L, name = "Keep This", description = "Old Desc")
        val updatedSlot = slot<PrincipalCredential>()

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { credentialsRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }

        service.editToken(1L, null, "New Desc", null, principalId)

        val updatedAttrs = updatedSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals("Keep This", updatedAttrs.name)
        assertEquals("New Desc", updatedAttrs.description)
    }

    @Test
    fun `editToken preserves description when null passed`() = runTest {
        val credential = createMockCredential(id = 1L, name = "Token", description = "Preserved")
        val updatedSlot = slot<PrincipalCredential>()

        coEvery { credentialsRepository.getById(1L) } returns credential
        coEvery { credentialsRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }

        service.editToken(1L, null, null, null, principalId)

        val updatedAttrs = updatedSlot.captured.attributes as ApiTokenCredentialAttributes
        assertEquals("Preserved", updatedAttrs.description)
    }

    // --- Validation ---

    @Test
    fun `createToken rejects blank name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId = principalId,
                input = ApiTokenInput(name = "   "),
                createdBy = principalId,
            )
        }
    }

    @Test
    fun `createToken rejects invalid expiration timestamp`() = runTest {
        setupForCreate()

        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId = principalId,
                input = ApiTokenInput(name = "Token", expiresAt = "not-a-date"),
                createdBy = principalId,
            )
        }
    }

    // --- Authenticate edge cases ---

    @Test
    fun `authenticate fails when principal is deleted`() = runTest {
        val rawToken = "bsk_a1b2c3d4_deletedPrincipal123456789"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val credential = createMockCredential(id = 1L, identifier = "sha256:$hash")

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns null

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `authenticate fails with corrupt expiration in stored credential`() = runTest {
        val rawToken = "bsk_a1b2c3d4_corruptExpiry12345678901"
        val hash = ApiTokenServiceImpl.sha256Hex(rawToken)
        val credential = createMockCredential(
            id = 1L,
            identifier = "sha256:$hash",
            expiresAt = "garbage-timestamp",
        )

        coEvery { credentialsRepository.getByIdentifier("sha256:$hash", CredentialType.API_TOKEN) } returns listOf(credential)

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
    }

    // --- getAndAuthorize edge cases ---

    @Test
    fun `revokeToken fails for non-existent credential`() = runTest {
        coEvery { credentialsRepository.getById(999L) } returns null

        assertFailsWith<SecurityException> {
            service.revokeToken(999L, principalId)
        }
    }

    @Test
    fun `revokeToken fails for non-API_TOKEN credential`() = runTest {
        val passwordCredential = mockk<PrincipalCredential>()
        every { passwordCredential.type } returns CredentialType.PASSWORD

        coEvery { credentialsRepository.getById(1L) } returns passwordCredential

        assertFailsWith<SecurityException> {
            service.revokeToken(1L, principalId)
        }
    }

    // --- getTokenById ---

    @Test
    fun `getTokenById returns null for non-API_TOKEN credential`() = runTest {
        val passwordCredential = mockk<PrincipalCredential>()
        every { passwordCredential.type } returns CredentialType.PASSWORD

        coEvery { credentialsRepository.getById(1L) } returns passwordCredential

        assertNull(service.getTokenById(1L))
    }

    @Test
    fun `getTokenById returns null for non-existent credential`() = runTest {
        coEvery { credentialsRepository.getById(999L) } returns null

        assertNull(service.getTokenById(999L))
    }

    // --- SHA-256 ---

    @Test
    fun `sha256Hex produces consistent 64-char hex output`() {
        val hash = ApiTokenServiceImpl.sha256Hex("test input")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })

        // Same input always produces same output
        assertEquals(hash, ApiTokenServiceImpl.sha256Hex("test input"))
    }

    @Test
    fun `sha256Hex produces different hashes for different inputs`() {
        val hash1 = ApiTokenServiceImpl.sha256Hex("input one")
        val hash2 = ApiTokenServiceImpl.sha256Hex("input two")
        assertTrue(hash1 != hash2)
    }

    // --- Base62 ---

    @Test
    fun `base62Encode produces non-empty alphanumeric output`() {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val encoded = ApiTokenServiceImpl.base62Encode(bytes)
        assertTrue(encoded.isNotEmpty())
        assertTrue(encoded.all { it.isLetterOrDigit() })
    }

    @Test
    fun `base62Encode is deterministic`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val first = ApiTokenServiceImpl.base62Encode(bytes)
        val second = ApiTokenServiceImpl.base62Encode(bytes)
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `base62Encode maps zero to an empty representation`() {
        assertEquals("", ApiTokenServiceImpl.base62Encode(byteArrayOf(0)))
    }

    @Test
    fun `createToken enforces name description and future expiration bounds`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId,
                ApiTokenInput(name = "x".repeat(256)),
                principalId,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId,
                ApiTokenInput(name = "token", description = "x".repeat(1001)),
                principalId,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createToken(
                principalId,
                ApiTokenInput(
                    name = "token",
                    expiresAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                ),
                principalId,
            )
        }
    }

    @Test
    fun `createToken accepts a description at the maximum length`() = runTest {
        setupForCreate()

        val result = service.createToken(
            principalId,
            ApiTokenInput(name = "token", description = "x".repeat(1000)),
            principalId,
        )

        assertEquals(1000, result.credential.attributes.let {
            (it as ApiTokenCredentialAttributes).description?.length
        })
    }

    @Test
    fun `createToken permits scopes exactly covered by a group grant`() = runTest {
        val credentialSlot = slot<PrincipalCredential>()
        coEvery {
            credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        coEvery {
            credentialsRepository.add(capture(credentialSlot))
        } answers { credentialSlot.captured.copy(id = 1L) }

        service.createToken(
            principalId,
            ApiTokenInput(name = "token", scopes = listOf("content:edit")),
            principalId,
        )

        assertEquals(
            listOf("content:edit"),
            (credentialSlot.captured.attributes as ApiTokenCredentialAttributes).scopes,
        )
    }

    @Test
    fun `createToken rejects scopes outside non-admin group grants`() = runTest {
        coEvery {
            credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)

        assertFailsWith<SecurityException> {
            service.createToken(
                principalId,
                ApiTokenInput(name = "token", scopes = listOf("storage:write")),
                principalId,
            )
        }
    }

    @Test
    fun `administrator and super-admin creation bypasses scope grants`() = runTest {
        val saGroup = Group(
            id = UUID.random(),
            name = "sa",
            description = "Super administrators",
            type = GroupType.SYSTEM,
        )
        coEvery {
            credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        } returns emptyList()
        coEvery {
            credentialsRepository.add(any())
        } answers { firstArg<PrincipalCredential>().copy(id = 1L) }
        coEvery {
            securityService.getPrincipalGroups(principalId)
        } returns listOf(adminGroup) andThen listOf(saGroup)

        service.createToken(
            principalId,
            ApiTokenInput(name = "admin", scopes = listOf("storage:write")),
            principalId,
        )
        service.createToken(
            principalId,
            ApiTokenInput(name = "sa", scopes = listOf("storage:write")),
            principalId,
        )
    }

    @Test
    fun `unknown token negative cache prevents repeated repository lookup`() = runTest {
        val rawToken = "bsk_deadbeef_negativeCacheUnique123456"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        coEvery {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        } returns emptyList()

        repeat(2) {
            assertFailsWith<SecurityException> { service.authenticate(rawToken, null) }
        }

        coVerify(exactly = 1) {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        }
    }

    @Test
    fun `successful authentication cache avoids duplicate repository and last-used writes`() = runTest {
        val rawToken = "bsk_deadbeef_authCacheUnique123456789"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        val credential = createMockCredential(id = 81L, identifier = identifier)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        coEvery {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit

        service.authenticate(rawToken, "192.0.2.1")
        service.authenticate(rawToken, "192.0.2.2")

        coVerify(exactly = 1) {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        }
        coVerify(exactly = 1) {
            credentialsRepository.updateLastUsed(81L, any(), "192.0.2.1")
        }
    }

    @Test
    fun `soft deleted principal is rejected before token authentication is cached`() = runTest {
        val rawToken = "bsk_scratch_deleted_principal"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        coEvery { credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN) } returns
            listOf(createMockCredential(id = 182L, identifier = identifier))
        coEvery { securityService.getPrincipalById(principalId) } returns
            Principal(id = principalId, anonymous = false, deletedAt = java.time.OffsetDateTime.now())
        assertFailsWith<SecurityException> { service.authenticate(rawToken, null) }
        coVerify(exactly = 0) { credentialsRepository.updateLastUsed(any(), any(), any()) }
    }

    @Test
    fun `cached tokens recheck account state on every instance`() = runTest {
        val rawToken = "bsk_scratch_deleted_cached"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        var principal: Principal? = Principal(id = principalId, anonymous = false)
        coEvery { credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN) } returns
            listOf(createMockCredential(id = 183L, identifier = identifier))
        coEvery { securityService.getPrincipalById(principalId) } answers { principal }
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit
        val otherInstance = ApiTokenServiceImpl(credentialsRepository, securityService, groupEvaluator, pubSubService)
        val instances = listOf(service, otherInstance)
        instances.forEach { assertEquals(principalId, it.authenticate(rawToken, null).id) }
        principal = principal?.copy(deletedAt = java.time.OffsetDateTime.now())
        instances.forEach { assertFailsWith<SecurityException> { it.authenticate(rawToken, null) } }
        principal = principal?.copy(deletedAt = null, anonymous = true)
        instances.forEach { assertFailsWith<SecurityException> { it.authenticate(rawToken, null) } }
        principal = null
        instances.forEach { assertFailsWith<SecurityException> { it.authenticate(rawToken, null) } }
    }

    @Test
    fun `cached authentication revalidates an expiration that passes after caching`() = runTest {
        val rawToken = "bsk_deadbeef_cacheExpiryUnique123456789"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        val credential = createMockCredential(
            id = 811L,
            identifier = identifier,
            expiresAt = OffsetDateTime.now().plusNanos(300_000_000).toString(),
        )
        coEvery {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        } returns listOf(credential)
        coEvery {
            securityService.getPrincipalById(principalId)
        } returns Principal(id = principalId, anonymous = false, verified = true)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery { credentialsRepository.updateLastUsed(any(), any(), any()) } returns Unit

        service.authenticate(rawToken, null)
        withContext(Dispatchers.Default) {
            delay(400)
        }

        assertFailsWith<SecurityException> {
            service.authenticate(rawToken, null)
        }
        coVerify(exactly = 1) {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        }
    }

    @Test
    fun `last-used cancellation propagates on fresh and cached authentication`() = runTest {
        suspend fun preparedService(
            suffix: String,
            updateFailure: Throwable,
        ): Pair<ApiTokenServiceImpl, String> {
            val token = "bsk_deadbeef_cancel_${suffix}_123456789"
            val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(token)}"
            val credential = createMockCredential(id = suffix.hashCode().toLong(), identifier = identifier)
            val repository = mockk<PrincipalCredentialsRepository>()
            val security = mockk<SecurityService>()
            val evaluator = GroupEvaluator(security)
            coEvery {
                repository.getByIdentifier(identifier, CredentialType.API_TOKEN)
            } returns listOf(credential)
            coEvery {
                security.getPrincipalById(principalId)
            } returns Principal(id = principalId, anonymous = false, verified = true)
            coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
            coEvery { repository.updateLastUsed(any(), any(), any()) } throws updateFailure
            return ApiTokenServiceImpl(repository, security, evaluator, pubSubService) to token
        }

        val (freshService, freshToken) = preparedService(
            "fresh",
            kotlinx.coroutines.CancellationException("cancel fresh"),
        )
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            freshService.authenticate(freshToken, null)
        }

        val cachedToken = "bsk_deadbeef_cancel_cached_123456789"
        val cachedIdentifier = "sha256:${ApiTokenServiceImpl.sha256Hex(cachedToken)}"
        val cachedCredential = createMockCredential(id = 812L, identifier = cachedIdentifier)
        val cachedRepository = mockk<PrincipalCredentialsRepository>()
        val cachedSecurity = mockk<SecurityService>()
        coEvery {
            cachedRepository.getByIdentifier(cachedIdentifier, CredentialType.API_TOKEN)
        } returns listOf(cachedCredential)
        coEvery {
            cachedSecurity.getPrincipalById(principalId)
        } returns Principal(id = principalId, anonymous = false, verified = true)
        coEvery { cachedSecurity.getPrincipalGroups(principalId) } returns emptyList()
        coEvery {
            cachedRepository.updateLastUsed(any(), any(), any())
        } throws IllegalStateException("first write fails") andThenThrows
            kotlinx.coroutines.CancellationException("cancel cached")
        val cachedService = ApiTokenServiceImpl(
            cachedRepository,
            cachedSecurity,
            GroupEvaluator(cachedSecurity),
            pubSubService,
        )

        cachedService.authenticate(cachedToken, null)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            cachedService.authenticate(cachedToken, null)
        }
    }

    @Test
    fun `last-used write failures do not prevent fresh or cached authentication`() = runTest {
        val rawToken = "bsk_deadbeef_lastUsedFailureUnique12345"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        val credential = createMockCredential(id = 82L, identifier = identifier)
        val principal = Principal(id = principalId, anonymous = false, verified = true)
        coEvery {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        } returns listOf(credential)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery {
            credentialsRepository.updateLastUsed(any(), any(), any())
        } throws IllegalStateException("database unavailable")

        assertEquals(principalId, service.authenticate(rawToken, null).id)
        assertEquals(principalId, service.authenticate(rawToken, null).id)
    }

    @Test
    fun `authenticate rejects malformed allowed group identifiers`() = runTest {
        val rawToken = "bsk_deadbeef_badGroupUnique1234567890"
        val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
        val credential = createMockCredential(
            id = 83L,
            identifier = identifier,
            allowedGroups = listOf("not-a-uuid"),
        )
        coEvery {
            credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
        } returns listOf(credential)
        coEvery {
            securityService.getPrincipalById(principalId)
        } returns Principal(id = principalId, anonymous = false)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()

        assertFailsWith<IllegalArgumentException> {
            service.authenticate(rawToken, null)
        }
    }

    @Test
    fun `last-used IP sanitization accepts IPv6 and rejects unsafe values`() = runTest {
        suspend fun authenticateWithIp(id: Long, suffix: String, ip: String?): String? {
            val rawToken = "bsk_deadbeef_ip${suffix}Unique123456789"
            val identifier = "sha256:${ApiTokenServiceImpl.sha256Hex(rawToken)}"
            val credential = createMockCredential(id = id, identifier = identifier)
            val capturedIp = slot<String?>()
            coEvery {
                credentialsRepository.getByIdentifier(identifier, CredentialType.API_TOKEN)
            } returns listOf(credential)
            coEvery {
                securityService.getPrincipalById(principalId)
            } returns Principal(id = principalId, anonymous = false)
            coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
            coEvery {
                credentialsRepository.updateLastUsed(id, any(), captureNullable(capturedIp))
            } returns Unit
            service.authenticate(rawToken, ip)
            return capturedIp.captured
        }

        assertEquals("0:0:0:0:0:0:0:1", authenticateWithIp(84L, "v6", "::1"))
        assertNull(authenticateWithIp(88L, "mapped", "::ffff:127.0.0.1"))
        assertNull(authenticateWithIp(85L, "bad4", "999.999.999.999"))
        assertNull(authenticateWithIp(86L, "plain", "localhost"))
        assertNull(authenticateWithIp(87L, "long", "1".repeat(46)))
    }

    @Test
    fun `token lookup methods delegate and filter credential types`() = runTest {
        val apiToken = createMockCredential(id = 90L)
        coEvery {
            credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN)
        } returns listOf(apiToken)
        coEvery { credentialsRepository.getById(90L) } returns apiToken

        assertEquals(listOf(apiToken), service.getTokensForPrincipal(principalId))
        assertEquals(apiToken, service.getTokenById(90L))
    }

    @Test
    fun `authorization rejects missing requesting principal`() = runTest {
        val requester = UUID.random()
        coEvery { credentialsRepository.getById(91L) } returns createMockCredential(id = 91L)
        coEvery { securityService.getPrincipalById(requester) } returns null

        assertFailsWith<SecurityException> {
            service.revokeToken(91L, requester)
        }
    }

    @Test
    fun `editToken rejects validation failures revoked tokens and scope escalation`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.editToken(1L, " ", null, null, principalId)
        }
        assertFailsWith<IllegalArgumentException> {
            service.editToken(1L, "x".repeat(256), null, null, principalId)
        }
        assertFailsWith<IllegalArgumentException> {
            service.editToken(1L, null, "x".repeat(1001), null, principalId)
        }
        assertFailsWith<IllegalArgumentException> {
            service.editToken(1L, null, null, listOf("invalid:scope"), principalId)
        }

        coEvery {
            credentialsRepository.getById(92L)
        } returns createMockCredential(id = 92L, revokedAt = OffsetDateTime.now().toString())
        assertFailsWith<SecurityException> {
            service.editToken(92L, "name", null, null, principalId)
        }

        coEvery {
            credentialsRepository.getById(93L)
        } returns createMockCredential(id = 93L)
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        assertFailsWith<SecurityException> {
            service.editToken(93L, null, null, listOf("storage:write"), principalId)
        }
    }

    @Test
    fun `editToken permits administrator scope changes and preserves prior scopes when absent`() = runTest {
        val credential = createMockCredential(
            id = 94L,
            scopes = listOf("content:view"),
        )
        val updated = slot<PrincipalCredential>()
        coEvery { credentialsRepository.getById(94L) } returns credential
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(adminGroup)
        coEvery {
            credentialsRepository.update(capture(updated))
        } answers { updated.captured }

        service.editToken(
            94L,
            null,
            null,
            listOf("storage:write"),
            principalId,
        )
        assertEquals(
            listOf("storage:write"),
            (updated.captured.attributes as ApiTokenCredentialAttributes).scopes,
        )
    }

    @Test
    fun `invalidation publish failures do not prevent revocation but cancellation propagates`() = runTest {
        val failureCredential = createMockCredential(id = 95L, identifier = "sha256:publish-failure")
        coEvery { credentialsRepository.getById(95L) } returns failureCredential
        coEvery { credentialsRepository.update(any()) } answers { firstArg() }
        coEvery {
            pubSubService.publish(any(), any<kotlinx.serialization.SerializationStrategy<String>>(), any())
        } throws IllegalStateException("broker unavailable")

        service.revokeToken(95L, principalId)

        val cancellationCredential = createMockCredential(id = 96L, identifier = "sha256:cancellation")
        coEvery { credentialsRepository.getById(96L) } returns cancellationCredential
        coEvery {
            pubSubService.publish(any(), any<kotlinx.serialization.SerializationStrategy<String>>(), any())
        } throws kotlin.coroutines.cancellation.CancellationException("cancelled")

        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            service.revokeToken(96L, principalId)
        }
    }

    // --- Helpers ---

    private fun setupForCreate(credentialSlot: io.mockk.CapturingSlot<PrincipalCredential>? = null) {
        coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.API_TOKEN) } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(editorsGroup)
        if (credentialSlot != null) {
            coEvery { credentialsRepository.add(capture(credentialSlot)) } answers { credentialSlot.captured.copy(id = 1L) }
        } else {
            coEvery { credentialsRepository.add(any()) } answers { firstArg<PrincipalCredential>().copy(id = 1L) }
        }
    }

    private fun createMockCredential(
        id: Long = 1L,
        identifier: String = "sha256:mockhash",
        name: String = "Test Token",
        description: String? = null,
        scopes: List<String>? = null,
        allowedGroups: List<String>? = null,
        expiresAt: String? = null,
        revokedAt: String? = null,
    ): PrincipalCredential {
        val attrs = ApiTokenCredentialAttributes(
            identifier = identifier,
            name = name,
            description = description,
            tokenPrefix = "bsk_a1b2c3d4",
            scopes = scopes,
            allowedGroups = allowedGroups,
            expiresAt = expiresAt,
            revokedAt = revokedAt,
            createdBy = principalId.toString(),
        )
        return PrincipalCredential(principal = principalId, attributes = attrs).copy(id = id)
    }
}
