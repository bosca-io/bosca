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
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.events.CredentialLinked
import bosca.security.events.EmailVerificationRequested
import bosca.security.events.PrincipalSignedIn
import bosca.security.events.SecurityAlertEmailRequested
import bosca.security.events.WelcomeEmailRequested
import bosca.security.events.dispatch
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.CredentialType
import bosca.security.model.HashedEncodedPassword
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredential
import bosca.security.model.SimplePasswordAttributes
import bosca.security.repository.GroupRepository
import bosca.security.repository.PrincipalCredentialsRepository
import bosca.security.repository.PrincipalEmailRepository
import bosca.security.repository.PrincipalExchangeTokenRepository
import bosca.security.repository.PrincipalGroupRepository
import bosca.security.repository.PrincipalRefreshTokenRepository
import bosca.security.repository.PrincipalRepository
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun pwHash(value: String): HashedEncodedPassword =
    object : HashedEncodedPassword { override val hash = value }

/**
 * Verifies the sign-in / credential event contract: every interactive login method stamps its
 * method string, per-request basic auth stays silent, and the idempotent credential attach
 * does not masquerade as a new link.
 */
@OptIn(InternalDI::class)
class SecurityServiceEventsTest {

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
    private val communityService = mockk<ObjectProvider<CommunityService>>()
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

    private val signedInEvents = mutableListOf<PrincipalSignedIn>()
    private val linkedEvents = mutableListOf<CredentialLinked>()
    private val verificationEmailRequests = mutableListOf<EmailVerificationRequested>()
    private val welcomeEmailRequests = mutableListOf<WelcomeEmailRequested>()
    private val securityAlertRequests = mutableListOf<SecurityAlertEmailRequested>()

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

        mockkStatic("bosca.security.events.PrincipalSignedInExtKt")
        mockkStatic("bosca.security.events.CredentialLinkedExtKt")
        mockkStatic("bosca.security.events.EmailVerificationRequestedExtKt")
        mockkStatic("bosca.security.events.WelcomeEmailRequestedExtKt")
        mockkStatic("bosca.security.events.SecurityAlertEmailRequestedExtKt")
        coEvery { any<PrincipalSignedIn>().dispatch() } coAnswers { signedInEvents += firstArg<PrincipalSignedIn>() }
        coEvery { any<CredentialLinked>().dispatch() } coAnswers { linkedEvents += firstArg<CredentialLinked>() }
        coEvery { any<EmailVerificationRequested>().dispatch() } coAnswers {
            verificationEmailRequests += firstArg<EmailVerificationRequested>()
        }
        coEvery { any<WelcomeEmailRequested>().dispatch() } coAnswers {
            welcomeEmailRequests += firstArg<WelcomeEmailRequested>()
        }
        coEvery { any<SecurityAlertEmailRequested>().dispatch() } coAnswers {
            securityAlertRequests += firstArg<SecurityAlertEmailRequested>()
        }

        coEvery { profileService.get() } returns profileServiceInstance
        coEvery { profileServiceInstance.getByPrincipal(any()) } returns emptyList()

        val config = mockk<SecurityConfiguration>()
        every { config.secret } returns "secret"
        every { config.issuer } returns "issuer"
        every { config.audience } returns "audience"
        every { config.expirationTimeInSeconds } returns 3600
        every { config.algorithm } returns Algorithm.HMAC256("secret")
        every { config.appUrl } returns "https://app"
        every { config.securityAlertUrl } returns "https://profiles.test/security?tab=logins"
        every { config.welcomeUrl } returns "https://onboarding.test/start"
        every { config.allowedAppOrigins } returns listOf("https://studio.test")
        coEvery { securityConfiguration.get() } returns config

        coEvery { principalRepository.incrementTokenVersion(any()) } returns 1
        coEvery { principalRepository.revokeSessions(any()) } returns Unit
        coEvery { principalRepository.touchLastLogin(any()) } returns Unit
        coEvery { principalRepository.addLogin(any(), any()) } returns PrincipalLogin(1, UUID.NIL, "test")
        coEvery { principalRefreshTokensRepository.deleteByPrincipalId(any()) } returns Unit
        coEvery { principalRepository.getPrincipalIdByIdentifierAndType(any(), any()) } returns null
        coEvery { principalRepository.getPrincipalIdByIdentifier(any()) } returns null
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
        unmockkStatic("bosca.security.events.PrincipalSignedInExtKt")
        unmockkStatic("bosca.security.events.CredentialLinkedExtKt")
        unmockkStatic("bosca.security.events.EmailVerificationRequestedExtKt")
        unmockkStatic("bosca.security.events.WelcomeEmailRequestedExtKt")
        unmockkStatic("bosca.security.events.SecurityAlertEmailRequestedExtKt")
        ProviderRegistry.clear()
    }

    private fun stubPasswordAccount(principalId: UUID, primaryProfileId: UUID? = null): Principal {
        val principal = Principal(
            id = principalId,
            verified = true,
            anonymous = false,
            primaryProfileId = primaryProfileId,
        )
        coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(
            PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("user", pwHash("hashed")))
        )
        coEvery { principalRepository.getPrincipalById(principalId) } returns principal
        coEvery { argonPasswordEncoder.matches("pass", any()) } returns true
        return principal
    }

    @Test
    fun `interactive password login fires PrincipalSignedIn with method password`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            stubPasswordAccount(principalId)

            service.loginWithCredential(SimplePasswordAttributes("user", "pass"), false, emptyList())

            assertEquals(1, signedInEvents.size)
            assertEquals(principalId, signedInEvents.single().principalId)
            assertEquals("password", signedInEvents.single().method)
            coVerify(exactly = 1) { principalRepository.addLogin(principalId, "password") }
        }
    }

    @Test
    fun `interactive sign-in emits a profile-addressed security alert`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            stubPasswordAccount(principalId, profileId)

            service.loginWithCredential(SimplePasswordAttributes("user", "pass"), false, emptyList())

            val alert = securityAlertRequests.single()
            assertEquals(setOf(profileId), alert.recipientIds)
            assertEquals("New sign-in to your account", alert.event)
            assertEquals("Method", alert.details.single().label)
            assertEquals("password", alert.details.single().value)
            assertEquals("https://profiles.test/security?tab=logins", alert.reviewUrl)
        }
    }

    @Test
    fun `interactive sign-in falls back to the principal linked profile`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profileId = UUID.random()
            val principal = stubPasswordAccount(principalId)
            val profile = mockk<bosca.profile.model.Profile> {
                every { id } returns profileId
            }
            coEvery { profileServiceInstance.getPrimaryProfile(principal) } returns profile

            service.loginWithCredential(SimplePasswordAttributes("user", "pass"), false, emptyList())

            assertEquals(setOf(profileId), securityAlertRequests.single().recipientIds)
        }
    }

    @Test
    fun `welcome and verification delivery emit pipeline events`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId,
                    typeId = "bosca.profiles.email",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "test",
                    attributes = buildJsonObject { put("email", "person@example.com") },
                    verificationToken = "verify-token",
                    verificationOrigin = "https://studio.test",
                ),
            )

            service.sendWelcomeMessage(profileId)
            service.sendEmailVerificationMessage(profileId)

            assertEquals(
                WelcomeEmailRequested(setOf(profileId), "https://onboarding.test/start"),
                welcomeEmailRequests.single(),
            )
            assertEquals(setOf(profileId), verificationEmailRequests.single().recipientIds)
            assertEquals(
                "https://studio.test/auth/verify?token=verify-token",
                verificationEmailRequests.single().verifyUrl,
            )
        }
    }

    @Test
    fun `verification delivery requires an email attribute with a token`() = runTest {
        withRequestCache {
            val profileId = UUID.random()
            coEvery { profileServiceInstance.getAttributes(profileId) } returns emptyList()
            assertFailsWith<IllegalStateException> {
                service.sendEmailVerificationMessage(profileId)
            }

            coEvery { profileServiceInstance.getAttributes(profileId) } returns listOf(
                ProfileAttribute(
                    profile = profileId,
                    typeId = "bosca.profiles.email",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "test",
                    attributes = buildJsonObject { put("email", "person@example.com") },
                    verificationToken = null,
                ),
            )
            assertFailsWith<IllegalStateException> {
                service.sendEmailVerificationMessage(profileId)
            }
        }
    }

    @Test
    fun `per-request basic auth does not fire PrincipalSignedIn`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            stubPasswordAccount(principalId)
            coEvery { principalGroupsRepository.getPrincipalGroups(principalId) } returns emptyList()

            service.authenticateWithCredential(SimplePasswordAttributes("user", "pass"))

            assertEquals(0, signedInEvents.size)
            coVerify(exactly = 0) { principalRepository.addLogin(any(), any()) }
        }
    }

    @Test
    fun `per-request basic auth rejects an anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { credentialsRepository.getByIdentifier("user", CredentialType.PASSWORD) } returns listOf(
                PrincipalCredential(
                    principal = principalId,
                    attributes = CredentialPasswordAttributes("user", pwHash("hashed")),
                )
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns
                Principal(id = principalId, verified = true, anonymous = true)
            coEvery { argonPasswordEncoder.matches("pass", any()) } returns true

            assertFailsWith<SecurityException> {
                service.authenticateWithCredential(SimplePasswordAttributes("user", "pass"))
            }

            assertEquals(0, signedInEvents.size)
        }
    }

    @Test
    fun `refresh token login fires PrincipalSignedIn with method refresh_token`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            coEvery { principalRefreshTokensRepository.consumeToken("refresh") } returns bosca.security.model.RefreshToken(principalId, "refresh")
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            service.loginWithRefreshToken("refresh")

            assertEquals(1, signedInEvents.size)
            assertEquals("refresh_token", signedInEvents.single().method)
            coVerify(exactly = 1) { principalRepository.addLogin(principalId, "refresh_token") }
        }
    }

    @Test
    fun `linking a new credential fires CredentialLinked`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.add(any()) } returns mockk()

            service.linkCredentialToPrincipal(principalId, OAuth2CredentialAttributes("new-sub", null, null, "google"))

            assertEquals(1, linkedEvents.size)
            assertEquals(principalId, linkedEvents.single().principalId)
            assertEquals(CredentialType.OAUTH2, linkedEvents.single().credentialType)
        }
    }

    @Test
    fun `re-attaching an already-owned credential does not fire CredentialLinked`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val attributes = OAuth2CredentialAttributes("existing-sub", null, null, "google")
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            // The identifier already belongs to THIS principal → idempotent early return, no insert.
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType(attributes.identifier, CredentialType.OAUTH2) } returns principalId
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = attributes)
            )

            service.linkCredentialToPrincipal(principalId, attributes)

            assertEquals(0, linkedEvents.size)
        }
    }
}
