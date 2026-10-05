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
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.repository.GroupRepository
import bosca.security.repository.ConsumedExchangeToken
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
class ExchangeTokenTest {

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

        coEvery { profileService.get() } returns profileServiceInstance
        coEvery { principalRepository.touchLastLogin(any()) } returns Unit
        coEvery { principalRepository.addLogin(any(), any()) } returns PrincipalLogin(1, UUID.NIL, "test")
        coEvery { principalRepository.deleteExpiredLoginRevocations() } returns Unit
        coEvery { profileServiceInstance.getByPrincipal(any()) } returns emptyList()
        coEvery { organizationService.get() } returns organizationServiceInstance
        coEvery { communityService.get() } returns communityServiceInstance

        val config = mockk<SecurityConfiguration>()
        every { config.secret } returns "secret"
        every { config.issuer } returns "issuer"
        every { config.audience } returns "audience"
        every { config.expirationTimeInSeconds } returns 3600
        every { config.algorithm } returns Algorithm.HMAC256("secret")
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
    fun `createExchangeToken should generate token and persist it`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalExchangeTokenRepository.addExchangeToken(any(), any(), any(), any(), any(), any()) } returns Unit

            val token = service.createExchangeToken(principalId)

            assertNotNull(token)
            coVerify {
                principalExchangeTokenRepository.addExchangeToken(
                    token = token,
                    principalId = principalId,
                    created = any(),
                    expires = any()
                )
            }
        }
    }

    @Test
    fun `createExchangeToken should generate unique tokens`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalExchangeTokenRepository.addExchangeToken(any(), any(), any(), any(), any(), any()) } returns Unit

            val token1 = service.createExchangeToken(principalId)
            val token2 = service.createExchangeToken(principalId)

            assertNotNull(token1)
            assertNotNull(token2)
            assert(token1 != token2) { "Exchange tokens should be unique" }
        }
    }

    @Test
    fun `loginWithExchangeToken should return login response for valid token`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val exchangeToken = "valid-exchange-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns ConsumedExchangeToken(principalId, accountCreated = false, originator = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val result = service.loginWithExchangeToken(exchangeToken)

            assertNotNull(result)
            assertEquals(principalId, result.principalId)
            assertNotNull(result.token)
        }
    }

    @Test
    fun `loginWithExchangeToken should delete token after use to prevent replay`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val exchangeToken = "one-time-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns ConsumedExchangeToken(principalId, accountCreated = false, originator = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            service.loginWithExchangeToken(exchangeToken)

            coVerify(exactly = 1) {
                principalExchangeTokenRepository.consumeToken(exchangeToken)
            }
        }
    }

    @Test
    fun `createExchangeToken should persist accountCreated and originator`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalExchangeTokenRepository.addExchangeToken(any(), any(), any(), any(), any(), any()) } returns Unit

            val token = service.createExchangeToken(principalId, accountCreated = true, originator = "studio")

            assertNotNull(token)
            coVerify {
                principalExchangeTokenRepository.addExchangeToken(
                    token = token,
                    principalId = principalId,
                    accountCreated = true,
                    originator = "studio",
                    created = any(),
                    expires = any()
                )
            }
        }
    }

    @Test
    fun `loginWithExchangeToken echoes accountCreated and originator from the consumed token`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val exchangeToken = "context-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns
                ConsumedExchangeToken(principalId, accountCreated = true, originator = "studio")
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val result = service.loginWithExchangeToken(exchangeToken)

            assertEquals(true, result.accountCreated)
            assertEquals("studio", result.originator)
        }
    }

    @Test
    fun `loginWithExchangeToken should fail for expired or missing token`() = runTest {
        withRequestCache {
            coEvery { principalExchangeTokenRepository.consumeToken("expired-token") } returns null

            assertFailsWith<SecurityException> {
                service.loginWithExchangeToken("expired-token")
            }
        }
    }

    @Test
    fun `loginWithExchangeToken should fail for anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = true)
            val exchangeToken = "anon-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns ConsumedExchangeToken(principalId, accountCreated = false, originator = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal

            assertFailsWith<SecurityException> {
                service.loginWithExchangeToken(exchangeToken)
            }
        }
    }

    @Test
    fun `loginWithExchangeToken should fail if principal not found`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val exchangeToken = "orphan-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns ConsumedExchangeToken(principalId, accountCreated = false, originator = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            assertFailsWith<IllegalStateException> {
                service.loginWithExchangeToken(exchangeToken)
            }
        }
    }

    @Test
    fun `loginWithExchangeToken should generate refresh token`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, anonymous = false, verified = true)
            val exchangeToken = "refresh-test-token"

            coEvery { principalExchangeTokenRepository.consumeToken(exchangeToken) } returns ConsumedExchangeToken(principalId, accountCreated = false, originator = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val result = service.loginWithExchangeToken(exchangeToken)

            assertNotNull(result.refreshToken)
            coVerify {
                principalRefreshTokensRepository.addPrincipalRefreshToken(any(), eq(principalId), any(), any(), any())
            }
        }
    }

    @Test
    fun `deleteExpiredRefreshToken should also clean up expired exchange tokens`() = runTest {
        withRequestCache {
            coEvery { principalRefreshTokensRepository.deleteExpired() } returns Unit
            coEvery { principalExchangeTokenRepository.deleteExpired() } returns Unit

            service.deleteExpiredRefreshToken()

            coVerify {
                principalRefreshTokensRepository.deleteExpired()
                principalExchangeTokenRepository.deleteExpired()
            }
        }
    }
}
