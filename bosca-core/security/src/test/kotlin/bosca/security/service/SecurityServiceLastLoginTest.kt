package bosca.security.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
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
import bosca.security.repository.GroupRepository
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.security.repository.PrincipalExchangeTokenRepository
import bosca.security.repository.PrincipalGroupRepository
import bosca.security.repository.PrincipalRefreshTokenRepository
import bosca.security.repository.PrincipalRepository
import bosca.security.repository.PrincipalEmailRepository
import bosca.security.model.PrincipalLogin
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.serialization.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalDI::class)
class SecurityServiceLastLoginTest {

    private val groupRepository = mockk<GroupRepository>(relaxed = true)
    private val principalRepository = mockk<PrincipalRepository>(relaxed = true)
    private val principalGroupsRepository = mockk<PrincipalGroupRepository>(relaxed = true)
    private val principalRefreshTokensRepository = mockk<PrincipalRefreshTokenRepository>(relaxed = true)
    private val principalExchangeTokenRepository = mockk<PrincipalExchangeTokenRepository>(relaxed = true)
    private val credentialsRepository = mockk<PrincipalCredentialsRepository>(relaxed = true)
    private val principalEmailRepository = mockk<PrincipalEmailRepository>(relaxed = true)
    private val thirdPartyTokenVerifier = mockk<ThirdPartyTokenVerifier>(relaxed = true)
    private val pubSubService = testPubSubService()
    private val argonPasswordEncoder = mockk<PasswordEncoder<ArgonPassword>>(relaxed = true)
    private val scryptPasswordEncoder = mockk<ObjectProvider<PasswordEncoder<ScryptPassword>>>(relaxed = true)
    private val securityConfiguration = mockk<ObjectProvider<SecurityConfiguration>>(relaxed = true)
    private val profileService = mockk<ObjectProvider<ProfileService>>(relaxed = true)
    private val organizationService = mockk<ObjectProvider<OrganizationService>>(relaxed = true)
    private val communityService = mockk<ObjectProvider<CommunityService>>(relaxed = true)
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

    @Test
    fun `getPrincipalLastLogin should return last login date from the principal row`() = runTest {
        val principalId = UUID.random()
        val expectedLastLogin = OffsetDateTime.now()

        coEvery { principalRepository.getLastLogin(principalId) } returns expectedLastLogin

        val actualLastLogin = service.getPrincipalLastLogin(principalId)

        assertEquals(expectedLastLogin, actualLastLogin)
    }

    @Test
    fun `getPrincipalLastLogin should return null when the principal has never signed in`() = runTest {
        val principalId = UUID.random()

        coEvery { principalRepository.getLastLogin(principalId) } returns null

        assertEquals(null, service.getPrincipalLastLogin(principalId))
    }

    @Test
    fun `getPrincipalLogins returns the requested history page`() = runTest {
        val principalId = UUID.random()
        val expected = listOf(PrincipalLogin(id = 2, principalId = principalId, method = "passkey"))
        coEvery { principalRepository.getLogins(principalId, 10, 25) } returns expected

        assertEquals(expected, service.getPrincipalLogins(principalId, 10, 25))
    }
}
