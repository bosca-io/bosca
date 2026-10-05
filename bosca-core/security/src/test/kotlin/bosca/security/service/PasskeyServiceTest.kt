package bosca.security.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.community.service.CommunityService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.model.CredentialType
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredential
import bosca.security.repository.GroupRepository
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.security.repository.PrincipalExchangeTokenRepository
import bosca.security.repository.PrincipalGroupRepository
import bosca.security.repository.PrincipalRefreshTokenRepository
import bosca.security.repository.PrincipalRepository
import bosca.security.repository.PrincipalEmailRepository
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import com.auth0.jwt.algorithms.Algorithm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@OptIn(InternalDI::class)
class PasskeyServiceTest {

    private val groupRepository = mockk<GroupRepository>()
    private val principalRepository = mockk<PrincipalRepository>()
    private val principalGroupsRepository = mockk<PrincipalGroupRepository>()
    private val principalRefreshTokensRepository = mockk<PrincipalRefreshTokenRepository>()
    private val principalExchangeTokenRepository = mockk<PrincipalExchangeTokenRepository>()
    private val credentialsRepository = mockk<PrincipalCredentialsRepository>()
    private val principalEmailRepository = mockk<PrincipalEmailRepository>(relaxed = true)
    private val thirdPartyTokenVerifier = mockk<ThirdPartyTokenVerifier>(relaxed = true)
    private val pubSubService = testPubSubService()
    private val argonPasswordEncoder = mockk<PasswordEncoder<ArgonPassword>>()
    private val scryptPasswordEncoder = mockk<ObjectProvider<PasswordEncoder<ScryptPassword>>>()
    private val securityConfiguration = mockk<ObjectProvider<SecurityConfiguration>>()
    private val profileService = mockk<ObjectProvider<ProfileService>>()
    private val profileServiceInstance = mockk<ProfileService>(relaxed = true)
    private val organizationService = mockk<ObjectProvider<OrganizationService>>()
    private val organizationServiceInstance = mockk<OrganizationService>(relaxed = true)
    private val communityService = mockk<ObjectProvider<CommunityService>>()
    private val communityServiceInstance = mockk<CommunityService>(relaxed = true)
    private val attributeVerificationServiceProvider = mockk<ObjectProvider<bosca.profile.attribute.verification.AttributeVerificationService>>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private fun passkeyCredential(id: Long, principalId: UUID, attrs: PasskeyCredentialAttributes): PrincipalCredential {
        return PrincipalCredential(
            id = id,
            principal = principalId,
            type = CredentialType.PASSKEY,
            attributesJson = Json.encodeToJsonElement(PasskeyCredentialAttributes.serializer(), attrs)
        )
    }

    private lateinit var service: SecurityServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }
        provides<PubSubService> { pubSubService }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
        coEvery { principalRepository.touchLastLogin(any()) } returns Unit
        coEvery { principalRepository.addLogin(any(), any()) } returns PrincipalLogin(1, UUID.NIL, "test")

        coEvery { profileService.get() } returns profileServiceInstance
        coEvery { profileServiceInstance.getByPrincipal(any()) } returns emptyList()
        coEvery { organizationService.get() } returns organizationServiceInstance
        coEvery { communityService.get() } returns communityServiceInstance

        val config = mockk<SecurityConfiguration>()
        every { config.secret } returns "test-secret-that-is-at-least-32-bytes-long"
        every { config.issuer } returns "test-issuer"
        every { config.audience } returns "test-audience"
        every { config.expirationTimeInSeconds } returns 3600
        every { config.algorithm } returns Algorithm.HMAC256("test-secret-that-is-at-least-32-bytes-long")
        every { config.appUrl } returns "https://app"
        every { config.securityAlertUrl } returns "https://app/security"

        coEvery { securityConfiguration.get() } returns config

        service = SecurityServiceImpl(
            groupRepository,
            principalRepository,
            principalGroupsRepository,
            principalRefreshTokensRepository,
            principalExchangeTokenRepository,
            credentialsRepository,
            testJson,
            argonPasswordEncoder,
            scryptPasswordEncoder,
            securityConfiguration,
            profileService,
            organizationService,
            communityService,
            attributeVerificationServiceProvider,
            principalEmailRepository,
            thirdPartyTokenVerifier,
            pubSubService,
        )
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `addPasskeyCredential persists credential with correct type`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val attrs = PasskeyCredentialAttributes(
                identifier = "cred-id-abc",
                name = "Test Passkey",
                publicKeyCose = "cose-key-data",
                createdAt = "2026-05-03T00:00:00Z",
            )

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            val credSlot = slot<PrincipalCredential>()
            coEvery { credentialsRepository.add(capture(credSlot)) } answers {
                credSlot.captured.copy(id = 1L)
            }

            val result = service.addPasskeyCredential(principalId, attrs)

            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals(CredentialType.PASSKEY, credSlot.captured.type)
            assertEquals(principalId, credSlot.captured.principal)
        }
    }

    @Test
    fun `addPasskeyCredential rejects anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = true)
            val attrs = PasskeyCredentialAttributes(
                identifier = "cred-id",
                name = "Test",
                publicKeyCose = "key",
                createdAt = "2026-05-03T00:00:00Z",
            )

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<IllegalStateException> {
                service.addPasskeyCredential(principalId, attrs)
            }
        }
    }

    @Test
    fun `addPasskeyCredential rejects missing and unverified principals`() = runTest {
        val attrs = PasskeyCredentialAttributes(
            identifier = "cred-id",
            name = "Test",
            publicKeyCose = "key",
            createdAt = "2026-05-03T00:00:00Z",
        )

        withRequestCache {
            val missingId = UUID.random()
            coEvery { principalRepository.getPrincipalById(missingId) } returns null
            assertFailsWith<IllegalStateException> {
                service.addPasskeyCredential(missingId, attrs)
            }
        }
        withRequestCache {
            val unverifiedId = UUID.random()
            coEvery { principalRepository.getPrincipalById(unverifiedId) } returns
                Principal(id = unverifiedId, verified = false, anonymous = false)
            assertFailsWith<IllegalStateException> {
                service.addPasskeyCredential(unverifiedId, attrs)
            }
        }
    }

    @Test
    fun `loginWithPasskey returns tokens for valid credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false, tokenVersion = 0)
            val credentialId = "matching-cred-id"
            val attrs = PasskeyCredentialAttributes(
                identifier = credentialId,
                name = "My Passkey",
                publicKeyCose = "key-data",
                createdAt = "2026-05-01T00:00:00Z",
            )
            val credential = passkeyCredential(10L, principalId, attrs)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.PASSKEY) } returns listOf(credential)
            coEvery { credentialsRepository.update(any()) } answers { firstArg() }
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val response = service.loginWithPasskey(principalId, credentialId, generateRefreshToken = true)

            assertNotNull(response)
            assertNotNull(response.token.token)
            assertNotNull(response.refreshToken)
            assertEquals(principalId, response.principalId)

            coVerify { credentialsRepository.update(match { it.id == 10L }) }
        }
    }

    @Test
    fun `loginWithPasskey throws for non-existent credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId, CredentialType.PASSKEY) } returns emptyList()

            assertFailsWith<SecurityException> {
                service.loginWithPasskey(principalId, "nonexistent-cred-id")
            }
        }
    }

    @Test
    fun `loginWithPasskey throws for unverified principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = false, anonymous = false)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.loginWithPasskey(principalId, "some-cred")
            }
        }
    }

    @Test
    fun `loginWithPasskey throws for anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = true)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.loginWithPasskey(principalId, "some-cred")
            }
        }
    }

    @Test
    fun `loginWithPasskey throws for a missing principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            assertFailsWith<SecurityException> {
                service.loginWithPasskey(principalId, "some-cred")
            }
        }
    }

    @Test
    fun `updatePasskeySignCount updates the stored counter`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val attrs = PasskeyCredentialAttributes(
                identifier = "cred-id",
                name = "Key",
                publicKeyCose = "pk",
                signCount = 5,
                createdAt = "2026-05-01T00:00:00Z",
            )
            val credential = passkeyCredential(42L, principalId, attrs)

            coEvery { credentialsRepository.getById(42L) } returns credential
            val updateSlot = slot<PrincipalCredential>()
            coEvery { credentialsRepository.update(capture(updateSlot)) } answers { updateSlot.captured }

            service.updatePasskeySignCount(42L, 10L)

            val updatedAttrs = updateSlot.captured.attributes as PasskeyCredentialAttributes
            assertEquals(10L, updatedAttrs.signCount)
            assertEquals("cred-id", updatedAttrs.identifier)
        }
    }

    @Test
    fun `updatePasskeySignCount rejects a missing credential`() = runTest {
        coEvery { credentialsRepository.getById(42L) } returns null

        assertFailsWith<IllegalStateException> {
            service.updatePasskeySignCount(42L, 10L)
        }
    }
}
