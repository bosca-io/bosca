package bosca.security.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.withRequestCache
import bosca.community.service.CommunityService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.ScryptPassword
import bosca.security.events.AccountLinkEmailRequested
import bosca.security.events.PasswordResetEmailRequested
import bosca.security.events.dispatch
import bosca.security.model.*
import bosca.security.oauth2.DefaultOauth2User
import bosca.security.repository.*
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import com.auth0.jwt.algorithms.Algorithm
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pwHash(value: String): HashedEncodedPassword =
    object : HashedEncodedPassword { override val hash = value }

@OptIn(InternalDI::class)
class SecurityServiceImplTest2 {

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
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val attributeVerificationService = mockk<bosca.profile.attribute.verification.AttributeVerificationService>(relaxed = true)
    private val attributeVerificationServiceProvider = mockk<ObjectProvider<bosca.profile.attribute.verification.AttributeVerificationService>>()
    private val jwtVerifier = mockk<com.auth0.jwt.interfaces.JWTVerifier>()
    private val accountLinkEmailRequests = mutableListOf<AccountLinkEmailRequested>()
    private val passwordResetEmailRequests = mutableListOf<PasswordResetEmailRequested>()

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
        provides<bosca.profile.attribute.verification.AttributeVerificationService> { attributeVerificationService }
        provides<PubSubService> { pubSubService }

        mockkStatic("bosca.security.events.AccountLinkEmailRequestedExtKt")
        mockkStatic("bosca.security.events.PasswordResetEmailRequestedExtKt")
        coEvery { any<AccountLinkEmailRequested>().dispatch() } coAnswers {
            accountLinkEmailRequests += firstArg<AccountLinkEmailRequested>()
        }
        coEvery { any<PasswordResetEmailRequested>().dispatch() } coAnswers {
            passwordResetEmailRequests += firstArg<PasswordResetEmailRequested>()
        }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        coEvery { profileService.get() } returns profileServiceInstance
        coEvery { attributeVerificationServiceProvider.get() } returns attributeVerificationService
        coEvery { profileServiceInstance.getByPrincipal(any()) } returns emptyList()
        coEvery { organizationService.get() } returns organizationServiceInstance
        coEvery { communityService.get() } returns communityServiceInstance
        // Default: no email is registered in the verified-email backstop (individual tests override).
        coEvery { principalEmailRepository.getByEmail(any()) } returns null

        // Class-wide stubs for the token-version mass-invalidation
        // path. `updatePassword` / `updateIdentifier` call
        // `bumpTokenVersion`, which hits both of these; stubbing at
        // the class level keeps individual tests focused on the
        // behavior under test.
        coEvery { principalRepository.incrementTokenVersion(any()) } returns 1
        coEvery { principalRefreshTokensRepository.deleteByPrincipalId(any()) } returns Unit
        coEvery { principalRepository.revokeSessions(any()) } returns Unit
        coEvery { principalRepository.markHasLoginRevocations(any()) } returns Unit
        // Every successful sign-in stamps principals.last_login.
        coEvery { principalRepository.touchLastLogin(any()) } returns Unit
        coEvery { principalRepository.addLogin(any(), any()) } returns PrincipalLogin(1, UUID.NIL, "test")
        // addPrincipal/attachCredential pre-check identifier ownership; default to "no existing owner".
        coEvery { principalRepository.getPrincipalIdByIdentifierAndType(any(), any()) } returns null
        coEvery { principalRepository.getPrincipalIdByIdentifier(any()) } returns null
        val config = mockk<SecurityConfiguration>()
        every { config.secret } returns "secret"
        every { config.issuer } returns "issuer"
        every { config.audience } returns "audience"
        every { config.verifier } returns jwtVerifier
        every { config.expirationTimeInSeconds } returns 3600
        every { config.algorithm } returns Algorithm.HMAC256("secret")
        every { config.appUrl } returns "https://app"
        every { config.securityAlertUrl } returns "https://app/security"
        every { config.welcomeUrl } returns "https://app/welcome"
        every { config.allowedAppOrigins } returns emptyList()
        
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
        unmockkStatic("bosca.security.events.AccountLinkEmailRequestedExtKt")
        unmockkStatic("bosca.security.events.PasswordResetEmailRequestedExtKt")
    }

    @Test
    fun `loginWithThirdParty should create new user and profile if credentials missing`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub123", "google")
            val user = DefaultOauth2User(
                id = "sub123",
                email = "test@example.com",
                name = "Test User",
                givenName = "Test",
                familyName = "User",
                picture = "http://example.com/pic.jpg",
                emailVerifiedClaim = true
            )
            val locale = Locale.US
            val signupTokens = listOf(SignupToken(SignupTokenType.ORGANIZATION, "token1"))
            
            // Mock credentials lookup: first empty (triggers creation), then found (login)
             coEvery { credentialsRepository.getByIdentifier("sub123", CredentialType.OAUTH2) } returnsMany listOf(
                emptyList(),
                listOf(
                    PrincipalCredential(
                        principal = UUID.random(), // ID doesn't matter here as we mock getPrincipalById next
                        attributes = credential
                    )
                )
            )
            
            // Mock creating principal
            val newPrincipalId = UUID.random()
            val newPrincipal = Principal(id = newPrincipalId, verified = true, anonymous = false)
            coEvery { principalRepository.add(any()) } returns newPrincipal
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { groupRepository.add(any()) } returns Group(id = UUID.random(), name = "group", description = "desc", type = GroupType.PRINCIPAL)
            coEvery { principalGroupsRepository.add(any()) } returns mockk()
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            
            // Mock profile creation
            val newProfile = Profile(id = UUID.random(), name = "Test User", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { profileServiceInstance.add(any(), any(), any()) } returns newProfile
            // Mock profile lookup for signup tokens processing
            coEvery { profileServiceInstance.getByPrincipal(newPrincipalId) } returns listOf(newProfile)
            
            // Mock principal lookup (used in second login attempt)
            coEvery { principalRepository.getPrincipalById(any()) } returns newPrincipal
            
            val response = service.loginWithThirdParty(credential, user, locale, true, signupTokens, originator = "studio")

            assertNotNull(response)
            // First-time OAuth signup: flagged as an account creation and the originator is echoed back.
            assertTrue(response.accountCreated)
            assertEquals("studio", response.originator)
            coVerify {
                principalRepository.add(match { it.verified })
                // The new credential is stamped with the originator (both original and last) of the sign-in.
                credentialsRepository.add(match { it.originator == "studio" && it.lastOriginator == "studio" })
                // New personal profiles are private by default — never PUBLIC at signup.
                profileServiceInstance.add(match { it.name == "Test User" && it.visibility == ProfileVisibility.USER }, ProfileType.GENERIC, newPrincipalId)
                organizationServiceInstance.addMemberByToken("token1", newPrincipalId)
                // the provider-verified email is claimed in the uniqueness backstop.
                principalEmailRepository.add("test@example.com", newPrincipalId)
                // The provider asserted email_verified, so the email attribute is stamped verified too
                // (source falls back to "oauth2" since this credential carries no explicit provider).
                profileServiceInstance.markVerified("bosca.profiles.email", listOf(newProfile.id), "email", "test@example.com", "oauth2")
            }
        }
    }

    @Test
    fun `loginWithThirdParty routes to linking when only the backstop owns the email`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub-x", null, null, "google")
            val user = DefaultOauth2User(id = "sub-x", email = "taken@example.com", name = "X", emailVerifiedClaim = true)
            val existingId = UUID.random()
            // No existing OAuth credential → create path; and NO verified PROFILE attribute owns the email…
            coEvery { credentialsRepository.getByIdentifier("sub-x", CredentialType.OAUTH2) } returns emptyList()
            coEvery { profileServiceInstance.getProfilesByEmail("taken@example.com") } returns emptyList()
            // …but the uniqueness backstop binds it to a verified principal (e.g. an OAuth-only account from the
            // V160 backfill whose email attribute V162 left unverified). getPrincipalByEmail now resolves through
            // the backstop, so signup is routed into account LINKING (with proof) rather than a raw conflict.
            coEvery { principalEmailRepository.getByEmail("taken@example.com") } returns existingId
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns listOf(
                PrincipalCredential(principal = existingId, attributes = OAuth2CredentialAttributes("their-sub", null, null, "google"))
            )

            assertFailsWith<AccountLinkRequired> {
                service.loginWithThirdParty(credential, user, Locale.US, true, emptyList())
            }
        }
    }

    @Test
    fun `loginWithThirdParty should return existing user if credentials exist`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub123", "google")
            val user = DefaultOauth2User(id = "sub123", email = "test@example.com", name = "Test User", givenName = null, familyName = null, picture = null)
            val locale = Locale.US
            
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            
            coEvery { credentialsRepository.getByIdentifier("sub123", CredentialType.OAUTH2) } returns listOf(
                PrincipalCredential(
                    principal = principalId,
                    attributes = credential
                )
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            coEvery { credentialsRepository.updateLastOriginator(any(), any()) } returns Unit

            val response = service.loginWithThirdParty(credential, user, locale, true, emptyList(), originator = "studio")

            assertNotNull(response)
            // Existing account: not a creation, but the originator is echoed back and recorded as the
            // credential's last originator.
            assertEquals(false, response.accountCreated)
            assertEquals("studio", response.originator)
            coVerify(exactly = 0) { principalRepository.add(any()) }
            coVerify { credentialsRepository.updateLastOriginator(any(), "studio") }
        }
    }

    @Test
    fun `loginWithThirdParty existing user without an originator leaves last_originator untouched`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub123", "google")
            val user = DefaultOauth2User(id = "sub123", email = "test@example.com", name = "Test User", givenName = null, familyName = null, picture = null)

            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)

            coEvery { credentialsRepository.getByIdentifier("sub123", CredentialType.OAUTH2) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = credential)
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()

            // No originator supplied on this login.
            val response = service.loginWithThirdParty(credential, user, Locale.US, true, emptyList())

            assertNull(response.originator)
            // Never overwrite a previously-recorded last originator with null.
            coVerify(exactly = 0) { credentialsRepository.updateLastOriginator(any(), any()) }
        }
    }

    @Test
    fun `loginWithThirdParty should send verification email if created user is not verified`() = runTest {
        withRequestCache {
             val credential = OAuth2CredentialAttributes("sub123", "google")
            // No email, so verified = false
            val user = DefaultOauth2User(id = "sub123", email = null, name = null, givenName = null, familyName = null, picture = null)
            val locale = Locale.US
            
             // Mock credentials lookup: first empty (triggers creation), then found (login)
             coEvery { credentialsRepository.getByIdentifier("sub123", CredentialType.OAUTH2) } returnsMany listOf(
                emptyList(),
                listOf(
                    PrincipalCredential(
                        principal = UUID.random(),
                        attributes = credential
                    )
                )
            )
            
            val newPrincipalId = UUID.random()
            val newPrincipal = Principal(id = newPrincipalId, verified = false, anonymous = false)
            coEvery { principalRepository.add(any()) } returns newPrincipal
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { groupRepository.add(any()) } returns Group(id = UUID.random(), name = "group", description = "desc", type = GroupType.PRINCIPAL)
            coEvery { principalGroupsRepository.add(any()) } returns mockk()
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            val newProfile = Profile(id = UUID.random(), name = "Test User", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { profileServiceInstance.add(any(), any(), any()) } returns newProfile
            
            // Mock profile lookup for email sending
            coEvery { profileServiceInstance.getByPrincipal(newPrincipalId) } returns listOf(Profile(id = UUID.random(), name = "P", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC))
            
            // Mock principal lookup (used in second login attempt)
            coEvery { principalRepository.getPrincipalById(any()) } returns newPrincipal
            coEvery { principalRepository.edit(any()) } returns newPrincipal

            val response = service.loginWithThirdParty(credential, user, locale, true, emptyList(), requestOrigin = "https://studio.test")

            assertNotNull(response)
            // sendVerificationEmail drives the generic framework, forwarding the request origin so the email
            // challenge routes back to the host the user started the OAuth flow on (not the default app origin).
            coVerify { attributeVerificationService.requestVerification(any(), "bosca.profiles.email", "https://studio.test") }
        }
    }

    @Test
    fun `loginWithThirdParty should not verify a present but unverified provider email`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub123", "google")
            // Email is present but the provider did NOT assert email_verified — must not confer verified status.
            val user = DefaultOauth2User(id = "sub123", email = "test@example.com", name = null, emailVerifiedClaim = false)
            val locale = Locale.US

            coEvery { credentialsRepository.getByIdentifier("sub123", CredentialType.OAUTH2) } returnsMany listOf(
                emptyList(),
                listOf(PrincipalCredential(principal = UUID.random(), attributes = credential))
            )

            val newPrincipalId = UUID.random()
            val newPrincipal = Principal(id = newPrincipalId, verified = false, anonymous = false)
            coEvery { principalRepository.add(any()) } returns newPrincipal
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { groupRepository.add(any()) } returns Group(id = UUID.random(), name = "group", description = "desc", type = GroupType.PRINCIPAL)
            coEvery { principalGroupsRepository.add(any()) } returns mockk()
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            val newProfile = Profile(id = UUID.random(), name = "Test User", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { profileServiceInstance.add(any(), any(), any()) } returns newProfile
            coEvery { profileServiceInstance.getByPrincipal(newPrincipalId) } returns listOf(newProfile)
            coEvery { principalRepository.getPrincipalById(any()) } returns newPrincipal
            coEvery { principalRepository.edit(any()) } returns newPrincipal

            val response = service.loginWithThirdParty(credential, user, locale, true, emptyList())

            assertNotNull(response)
            coVerify {
                principalRepository.add(match { !it.verified })
                // The unverified account is asked to prove its email through the generic framework.
                attributeVerificationService.requestVerification(any(), "bosca.profiles.email")
            }
            // The provider did NOT assert email_verified, so the email attribute must stay unverified.
            coVerify(exactly = 0) { profileServiceInstance.markVerified(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `linkCredentialToPrincipal attaches credential without invalidating sessions`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val credential = OAuth2CredentialAttributes("sub-xyz", null, null, "google")
            val stored = PrincipalCredential(principal = principalId, attributes = credential)

            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-xyz", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns stored

            val result = service.linkCredentialToPrincipal(principalId, credential)

            assertEquals(principalId, result.principal)
            coVerify {
                credentialsRepository.add(match { it.principal == principalId && it.type == CredentialType.OAUTH2 })
            }
            // Linking a new sign-in method is additive: it must NOT end the caller's own session or any
            // other outstanding session for the principal.
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(principalId) }
            coVerify(exactly = 0) { principalRefreshTokensRepository.deleteByPrincipalId(principalId) }
        }
    }

    @Test
    fun `linkCredentialToPrincipal rejects an identifier already owned by a principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val conflictId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-xyz", null, null, "google")

            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-xyz", any()) } returns conflictId
            coEvery { principalRepository.getPrincipalById(conflictId) } returns Principal(id = conflictId, anonymous = false)

            assertFailsWith<CredentialConflict> {
                service.linkCredentialToPrincipal(principalId, credential)
            }
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `linkCredentialToPrincipal maps a unique-constraint violation to CredentialConflict`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-xyz", null, null, "google")

            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            // Pre-check passes, but a concurrent insert wins the race — Postgres raises 23505.
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-xyz", any()) } returns null
            coEvery {
                credentialsRepository.add(any())
            } throws IllegalStateException("repository write failed", java.sql.SQLException("duplicate key", "23505"))

            assertFailsWith<CredentialConflict> {
                service.linkCredentialToPrincipal(principalId, credential)
            }
        }
    }

    @Test
    fun `linkCredentialToPrincipal preserves non-unique repository failures`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-xyz", null, null, "google")
            val failure = java.sql.SQLException("connection lost", "08006")

            coEvery {
                principalRepository.getPrincipalById(principalId)
            } returns Principal(id = principalId, anonymous = false)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("sub-xyz", any())
            } returns null
            coEvery { credentialsRepository.add(any()) } throws failure

            kotlin.test.assertSame(
                failure,
                assertFailsWith<java.sql.SQLException> {
                    service.linkCredentialToPrincipal(principalId, credential)
                },
            )
        }
    }

    @Test
    fun `linkCredentialToPrincipal encodes a password credential before storing`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = SimplePasswordAttributes("user@example.com", "plaintext")
            val stored = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("user@example.com", pwHash("hashed")))

            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("user@example.com", any()) } returns null
            // The account has no existing credential, so this password is its first (the single-password guard passes).
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns emptyList()
            coEvery { argonPasswordEncoder.encode("plaintext") } returns ArgonPassword("hashed")
            coEvery { credentialsRepository.add(any()) } returns stored

            service.linkCredentialToPrincipal(principalId, credential)

            coVerify {
                argonPasswordEncoder.encode("plaintext")
                credentialsRepository.add(match {
                    val attrs = it.attributes
                    attrs is CredentialPasswordAttributes && attrs.password == "hashed" && attrs.identifier == "user@example.com"
                })
            }
        }
    }

    @Test
    fun `linkCredentialToPrincipal rejects an anonymous principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = true)

            assertFailsWith<SecurityException> {
                service.linkCredentialToPrincipal(principalId, OAuth2CredentialAttributes("sub-xyz", null, null, "google"))
            }
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `connectThirdParty refuses a provider identity whose email is not verified`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-unverified", null, null, "google")
            // Provider asserts an email but did NOT mark it verified — must not be attachable even though
            // the caller is authenticated (the session proves the account, not control of that email).
            val user = DefaultOauth2User(id = "sub-unverified", email = "unverified@example.com", emailVerifiedClaim = false)

            assertFailsWith<SecurityException> {
                service.connectThirdParty(principalId, credential, user)
            }
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `connectThirdParty attaches a verified provider identity to the authenticated principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-verified", null, null, "google")
            val user = DefaultOauth2User(id = "sub-verified", email = "verified@example.com", emailVerifiedClaim = true)
            val stored = PrincipalCredential(principal = principalId, attributes = credential)

            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-verified", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns stored

            val result = service.connectThirdParty(principalId, credential, user)

            assertEquals(principalId, result.principal)
            coVerify {
                credentialsRepository.add(match { it.principal == principalId && it.type == CredentialType.OAUTH2 })
            }
            // Connecting a provider is additive — it must not invalidate outstanding sessions.
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(principalId) }
        }
    }

    @Test
    fun `connectThirdParty attaches a provider identity that carries no email`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-noemail", null, null, "google")
            // No asserted email → the verified-email guard does not apply; the session is the proof.
            val user = DefaultOauth2User(id = "sub-noemail", email = null, emailVerifiedClaim = false)
            val stored = PrincipalCredential(principal = principalId, attributes = credential)

            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-noemail", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns stored

            val result = service.connectThirdParty(principalId, credential, user)

            assertEquals(principalId, result.principal)
            coVerify { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `connectThirdParty treats a blank provider email as absent`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val credential = OAuth2CredentialAttributes("sub-blank-email", null, null, "google")
            val user = DefaultOauth2User(
                id = "sub-blank-email",
                email = "   ",
                emailVerifiedClaim = false,
            )
            val stored = PrincipalCredential(principal = principalId, attributes = credential)

            coEvery { principalRepository.getPrincipalById(principalId) } returns
                Principal(id = principalId, anonymous = false)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("sub-blank-email", any())
            } returns null
            coEvery { credentialsRepository.add(any()) } returns stored

            assertEquals(principalId, service.connectThirdParty(principalId, credential, user).principal)
        }
    }

    @Test
    fun `loginWithThirdPartyToken verifies the provider token via the seam then logs in the existing identity`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val user = DefaultOauth2User(id = "sub-token", email = "test@example.com", emailVerifiedClaim = true)
            // The sole network-bound step is now a mockable seam — so the token entry point is testable.
            coEvery { thirdPartyTokenVerifier.verify(ThirdPartyType.GOOGLE, "google-id-token") } returns user
            coEvery { credentialsRepository.getByIdentifier("sub-token", CredentialType.OAUTH2) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = OAuth2CredentialAttributes("sub-token", null, null, "google"))
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, verified = true, anonymous = false)
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()

            val response = service.loginWithThirdPartyToken(ThirdPartyType.GOOGLE, "google-id-token", Locale.US, true, emptyList())

            assertNotNull(response)
            coVerify { thirdPartyTokenVerifier.verify(ThirdPartyType.GOOGLE, "google-id-token") }
            coVerify(exactly = 0) { principalRepository.add(any()) }
        }
    }

    @Test
    fun `connectThirdParty by token verifies via the seam then attaches the identity`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val user = DefaultOauth2User(id = "sub-connect", email = "me@example.com", emailVerifiedClaim = true)
            coEvery { thirdPartyTokenVerifier.verify(ThirdPartyType.GOOGLE, "google-id-token") } returns user
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-connect", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns PrincipalCredential(
                principal = principalId,
                attributes = OAuth2CredentialAttributes("sub-connect", null, null, "google"),
            )

            val result = service.connectThirdParty(principalId, ThirdPartyType.GOOGLE, "google-id-token")

            assertEquals(principalId, result.principal)
            coVerify {
                thirdPartyTokenVerifier.verify(ThirdPartyType.GOOGLE, "google-id-token")
                credentialsRepository.add(match { it.attributes.identifier == "sub-connect" })
            }
        }
    }

    @Test
    fun `loginWithThirdParty requires linking when a verified account already owns the email`() = runTest {
        withRequestCache {
            val credential = OAuth2CredentialAttributes("sub-new", null, null, "google")
            val user = DefaultOauth2User(id = "sub-new", email = "taken@example.com", name = "New", emailVerifiedClaim = true)
            val existingId = UUID.random()

            // No existing OAuth credential for this sub → the create path is reached.
            coEvery { credentialsRepository.getByIdentifier("sub-new", CredentialType.OAUTH2) } returns emptyList()
            // …but a verified account already owns the provider-verified email.
            coEvery { profileServiceInstance.getProfilesByEmail("taken@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = existingId, name = "Existing", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns emptyList()

            assertFailsWith<AccountLinkRequired> {
                service.loginWithThirdParty(credential, user, Locale.US, true, emptyList())
            }
            coVerify(exactly = 0) { principalRepository.add(any()) }
        }
    }

    private val dummyPendingCredential = OAuth2CredentialAttributes("pending-sub", null, null, "google")

    @Test
    fun `requireEmailAvailableForSignup is a no-op for a missing email`() = runTest {
        withRequestCache {
            service.verifyEmailAvailableForSignup(null, dummyPendingCredential)
            service.verifyEmailAvailableForSignup("", dummyPendingCredential)
            service.verifyEmailAvailableForSignup("   ", dummyPendingCredential)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup is a no-op when no verified account owns the email`() = runTest {
        withRequestCache {
            coEvery { profileServiceInstance.getProfilesByEmail("free@example.com") } returns emptyList()
            service.verifyEmailAvailableForSignup("free@example.com", dummyPendingCredential)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup throws with proof methods when a verified account owns the email`() = runTest {
        withRequestCache {
            val existingId = UUID.random()
            coEvery { profileServiceInstance.getProfilesByEmail("taken@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = existingId, name = "Existing", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            // Existing account has a password → both PASSWORD and EMAIL proof methods are offered.
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns listOf(
                PrincipalCredential(principal = existingId, attributes = CredentialPasswordAttributes("taken@example.com", pwHash("hash")))
            )

            val ex = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup("taken@example.com", dummyPendingCredential)
            }
            assertEquals(existingId, ex.existingPrincipalId)
            assertTrue(ex.token.isNotBlank())
            assertEquals(listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL), ex.methods)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup offers only email proof for an account without a password`() = runTest {
        withRequestCache {
            val existingId = UUID.random()
            coEvery { profileServiceInstance.getProfilesByEmail("oauth@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = existingId, name = "OAuth", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns listOf(
                PrincipalCredential(principal = existingId, attributes = OAuth2CredentialAttributes("their-sub", null, null, "google"))
            )

            val ex = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup("oauth@example.com", dummyPendingCredential)
            }
            assertEquals(listOf(LinkProofMethod.EMAIL), ex.methods)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup ignores an unverified account owning the email`() = runTest {
        withRequestCache {
            val unverifiedId = UUID.random()
            // getPrincipalByEmail filters to verified principals, so an unverified owner must not block.
            coEvery { profileServiceInstance.getProfilesByEmail("pending@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = unverifiedId, name = "Pending", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(unverifiedId) } returns Principal(id = unverifiedId, verified = false, anonymous = false)

            service.verifyEmailAvailableForSignup("pending@example.com", dummyPendingCredential)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup links a password onto an account that has no password`() = runTest {
        withRequestCache {
            val existingId = UUID.random()
            coEvery { profileServiceInstance.getProfilesByEmail("oauth@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = existingId, name = "OAuth", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            // OAuth-only account → a password is a genuinely new sign-in method, so linking is offered.
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns listOf(
                PrincipalCredential(principal = existingId, attributes = OAuth2CredentialAttributes("their-sub", null, null, "google"))
            )

            // The OAuth-only account has no password, so the raw password is encoded for the pending link.
            coEvery { argonPasswordEncoder.encode("password") } returns ArgonPassword("hashed")

            val ex = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup("oauth@example.com", SimplePasswordAttributes("oauth@example.com", "password"))
            }
            assertEquals(existingId, ex.existingPrincipalId)
            assertEquals(listOf(LinkProofMethod.EMAIL), ex.methods)
        }
    }

    @Test
    fun `requireEmailAvailableForSignup fails when the account already has a password (nothing to link)`() = runTest {
        withRequestCache {
            val existingId = UUID.random()
            coEvery { profileServiceInstance.getProfilesByEmail("taken@example.com") } returns listOf(
                Profile(id = UUID.random(), principal = existingId, name = "Existing", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(existingId) } returns Principal(id = existingId, verified = true, anonymous = false)
            // Account already has a password → nothing to link; a password sign-up just fails outright.
            coEvery { credentialsRepository.getByPrincipalId(existingId) } returns listOf(
                PrincipalCredential(principal = existingId, attributes = CredentialPasswordAttributes("taken@example.com", pwHash("hash")))
            )

            assertFailsWith<CredentialConflict> {
                service.verifyEmailAvailableForSignup("taken@example.com", SimplePasswordAttributes("taken@example.com", "password"))
            }
        }
    }

    @Test
    fun `confirmAccountLinkWithPassword throws on an unknown or expired token`() = runTest {
        withRequestCache {
            assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithPassword("nope", "password")
            }
        }
    }

    @Test
    fun `account link operations reject missing pending state and missing target`() = runTest {
        withRequestCache {
            val cache = InMemoryStringCache()
            coEvery {
                cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any())
            } returns cache
            assertFailsWith<SecurityException> {
                service.requestAccountLinkEmailProof("missing")
            }

            val missingTargetId = UUID.random()
            PendingLinkStore(cache, testJson).putPendingLink(
                "missing-target",
                PendingLink(
                    targetPrincipalId = missingTargetId,
                    email = "owner@example.com",
                    credentialType = CredentialType.OAUTH2,
                    credentialAttributes = PrincipalCredential(
                        principal = missingTargetId,
                        attributes = OAuth2CredentialAttributes("new-sub", source = "google"),
                    ).attributesJson,
                    deliveryProfileId = UUID.random(),
                ),
            )
            coEvery { principalRepository.getPrincipalById(missingTargetId) } returns null

            assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithPassword("missing-target", "password")
            }
        }
    }

    @Test
    fun `password account link rejects unavailable password proof and a proof for another principal`() = runTest {
        withRequestCache {
            val cache = InMemoryStringCache()
            coEvery {
                cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any())
            } returns cache
            val targetId = UUID.random()
            val otherId = UUID.random()
            val target = Principal(id = targetId, verified = true, anonymous = false)
            val other = Principal(id = otherId, verified = true, anonymous = false)
            val passwordCredential = PrincipalCredential(
                principal = otherId,
                attributes = CredentialPasswordAttributes("owner@example.com", pwHash("hash")),
            )
            PendingLinkStore(cache, testJson).putPendingLink(
                "password-proof",
                PendingLink(
                    targetPrincipalId = targetId,
                    email = "owner@example.com",
                    credentialType = CredentialType.OAUTH2,
                    credentialAttributes = PrincipalCredential(
                        principal = targetId,
                        attributes = OAuth2CredentialAttributes("new-sub", source = "google"),
                    ).attributesJson,
                    deliveryProfileId = UUID.random(),
                ),
            )
            coEvery { principalRepository.getPrincipalById(targetId) } returns target
            coEvery { principalRepository.getPrincipalById(otherId) } returns other
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returnsMany
                listOf(emptyList(), listOf(passwordCredential))
            coEvery {
                credentialsRepository.getByIdentifier(
                    "owner@example.com",
                    CredentialType.PASSWORD,
                )
            } returns listOf(passwordCredential)
            coEvery { argonPasswordEncoder.matches("password", any()) } returns true
            coEvery { principalGroupsRepository.getPrincipalGroups(otherId) } returns emptyList()

            assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithPassword("password-proof", "password")
            }
            assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithPassword("password-proof", "password")
            }
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `account link email proof fails when no safe delivery profile was captured`() = runTest {
        withRequestCache {
            val cache = InMemoryStringCache()
            coEvery {
                cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any())
            } returns cache
            val targetId = UUID.random()
            PendingLinkStore(cache, testJson).putPendingLink(
                "no-delivery-profile",
                PendingLink(
                    targetPrincipalId = targetId,
                    email = "owner@example.com",
                    credentialType = CredentialType.OAUTH2,
                    credentialAttributes = PrincipalCredential(
                        principal = targetId,
                        attributes = OAuth2CredentialAttributes("new-sub", source = "google"),
                    ).attributesJson,
                    deliveryProfileId = null,
                ),
            )

            assertFailsWith<SecurityException> {
                service.requestAccountLinkEmailProof("no-delivery-profile")
            }
            assertTrue(accountLinkEmailRequests.isEmpty())
        }
    }

    @Test
    fun `confirmAccountLinkWithEmail throws on an unknown or expired token`() = runTest {
        withRequestCache {
            assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithEmail("nope")
            }
        }
    }

    @Test
    fun `account link round-trip via email proof attaches the credential and logs in`() = runTest {
        withRequestCache {
            val fakeCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any()) } returns fakeCache

            val targetId = UUID.random()
            val target = Principal(id = targetId, verified = true, anonymous = false)
            val email = "owner@example.com"
            // Existing verified OAuth-only account owns the email → email proof is the only option.
            coEvery { profileServiceInstance.getProfilesByEmail(email) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(targetId) } returns target
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returns listOf(
                PrincipalCredential(principal = targetId, attributes = OAuth2CredentialAttributes("existing-sub", null, null, "google"))
            )
            coEvery { profileServiceInstance.getByPrincipal(targetId) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType(any(), any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            // 1. Collision → a pending-link token is minted (no principal created).
            val pendingCredential = OAuth2CredentialAttributes("new-sub", null, null, "google")
            val challenge = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup(email, pendingCredential)
            }
            coVerify(exactly = 0) { principalRepository.add(any()) }

            // 2. Request the magic-link and recover the one-time token from the sent email.
            service.requestAccountLinkEmailProof(challenge.token)
            val emailToken = java.net.URLDecoder.decode(
                accountLinkEmailRequests.single().confirmUrl.substringAfter("proof="),
                "UTF-8",
            )

            // 3. Confirm with the emailed token → credential attached, logged in as the existing account.
            val response = service.confirmAccountLinkWithEmail(emailToken)

            assertEquals(targetId, response.principalId)
            coVerify {
                credentialsRepository.add(match { it.principal == targetId && it.type == CredentialType.OAUTH2 })
            }
            // Linking to the survivor is additive — it must not end the survivor's own sessions. (Only a
            // retired duplicate's tokens are invalidated, and there is no duplicate to retire here.)
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(targetId) }

            // 4. The token is single-use — a replay fails.
            assertFailsWith<SecurityException> { service.confirmAccountLinkWithEmail(emailToken) }
        }
    }

    @Test
    fun `verify-time account link retires the duplicate before attaching its credential`() = runTest {
        withRequestCache {
            val cache = InMemoryStringCache()
            coEvery {
                cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any())
            } returns cache
            val survivorId = UUID.random()
            val duplicateId = UUID.random()
            val survivor = Principal(id = survivorId, verified = true, anonymous = false)
            val duplicate = Principal(
                id = duplicateId,
                verified = true,
                anonymous = false,
                attributes = buildJsonObject { put("source", "signup") },
            )
            val duplicateCredential = PrincipalCredential(
                principal = duplicateId,
                attributes = OAuth2CredentialAttributes("new-sub", null, null, "google"),
            )
            val duplicateProfile = Profile(
                id = UUID.random(),
                principal = duplicateId,
                name = "Duplicate",
                type = ProfileType.GENERIC,
                visibility = ProfileVisibility.PUBLIC,
            )
            val pendingCredential = PrincipalCredential(
                principal = survivorId,
                attributes = OAuth2CredentialAttributes("new-sub", null, null, "google"),
            )
            val store = PendingLinkStore(cache, testJson)
            store.putPendingLink(
                "link-token",
                PendingLink(
                    targetPrincipalId = survivorId,
                    email = "owner@example.com",
                    credentialType = CredentialType.OAUTH2,
                    credentialAttributes = pendingCredential.attributesJson,
                    retirePrincipalId = duplicateId,
                    deliveryProfileId = UUID.random(),
                ),
            )
            store.putEmailProof("email-proof", "link-token")

            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(duplicateCredential)
            coEvery { credentialsRepository.getByPrincipalId(survivorId) } returns emptyList()
            coEvery {
                credentialsRepository.delete(duplicateId, CredentialType.OAUTH2, "new-sub")
            } returns Unit
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(duplicateProfile)
            coEvery { profileServiceInstance.delete(duplicateProfile.id) } returns Unit
            coEvery { credentialsRepository.add(any()) } answers { firstArg() }
            coEvery { principalRepository.edit(any()) } answers { firstArg() }
            coEvery {
                principalRefreshTokensRepository.addPrincipalRefreshToken(any(), survivorId, any(), any(), any())
            } returns Unit

            val response = service.confirmAccountLinkWithEmail("email-proof")

            assertEquals(survivorId, response.principalId)
            coVerify { credentialsRepository.delete(duplicateId, CredentialType.OAUTH2, "new-sub") }
            coVerify { profileServiceInstance.delete(duplicateProfile.id) }
            coVerify { principalEmailRepository.deleteByPrincipal(duplicateId) }
            coVerify { principalRepository.incrementTokenVersion(duplicateId) }
            coVerify {
                principalRepository.edit(
                    match {
                        !it.verified &&
                            it.anonymous &&
                            it.primaryProfileId == null &&
                            it.attributes.toString().contains(survivorId.toString())
                    }
                )
            }
        }
    }

    @Test
    fun `account link completion tolerates an already-retired duplicate and attaches a password`() = runTest {
        withRequestCache {
            val cache = InMemoryStringCache()
            coEvery {
                cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any())
            } returns cache
            val survivorId = UUID.random()
            val retiredDuplicateId = UUID.random()
            val survivor = Principal(id = survivorId, verified = true, anonymous = false)
            val password = CredentialPasswordAttributes("owner@example.com", pwHash("hash"))
            val store = PendingLinkStore(cache, testJson)
            store.putPendingLink(
                "link-token",
                PendingLink(
                    targetPrincipalId = survivorId,
                    email = "owner@example.com",
                    credentialType = CredentialType.PASSWORD,
                    credentialAttributes = PrincipalCredential(
                        principal = survivorId,
                        attributes = password,
                    ).attributesJson,
                    retirePrincipalId = retiredDuplicateId,
                    deliveryProfileId = UUID.random(),
                ),
            )
            store.putEmailProof("email-proof", "link-token")

            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.getPrincipalById(retiredDuplicateId) } returns null
            coEvery { credentialsRepository.getByPrincipalId(survivorId) } returns emptyList()
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType(
                    "owner@example.com",
                    CredentialType.PASSWORD,
                )
            } returns null
            coEvery { credentialsRepository.add(any()) } answers { firstArg() }
            coEvery {
                principalRefreshTokensRepository.addPrincipalRefreshToken(any(), survivorId, any(), any(), any())
            } returns Unit

            val response = service.confirmAccountLinkWithEmail("email-proof")

            assertEquals(survivorId, response.principalId)
            coVerify { credentialsRepository.add(match { it.type == CredentialType.PASSWORD }) }
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(retiredDuplicateId) }
        }
    }

    @Test
    fun `email proof delivers to an unverified-attribute profile for a backstop-only account`() = runTest {
        withRequestCache {
            val fakeCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any()) } returns fakeCache

            val targetId = UUID.random()
            val deliveryProfileId = UUID.random()
            val email = "backstop@example.com"
            // The account owns `email` ONLY via the uniqueness backstop (an OAuth-verified or V160-backfilled
            // address): no VERIFIED bosca.profiles.email attribute, so getProfilesByEmail returns nothing and
            // collision detection resolves the owner through the backstop instead.
            coEvery { profileServiceInstance.getProfilesByEmail(email) } returns emptyList()
            coEvery { principalEmailRepository.getByEmail(email) } returns targetId
            coEvery { principalRepository.getPrincipalById(targetId) } returns Principal(id = targetId, verified = true, anonymous = false)
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returns listOf(
                PrincipalCredential(principal = targetId, attributes = OAuth2CredentialAttributes("existing-sub", null, null, "google"))
            )
            // The principal still carries the matched address on a profile — just not flagged verified. The
            // delivery target must resolve to it (previously delivery threw "no longer verified" and stranded
            // the only available proof method for this account).
            val profile = Profile(id = deliveryProfileId, principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { profileServiceInstance.getByPrincipal(targetId) } returns listOf(profile)
            coEvery { profileServiceInstance.getAttributes(deliveryProfileId) } returns listOf(
                ProfileAttribute(
                    profile = deliveryProfileId,
                    typeId = "bosca.profiles.name",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "oauth2",
                    attributes = buildJsonObject { put("email", email) },
                    verified = false,
                ),
                ProfileAttribute(
                    profile = deliveryProfileId,
                    typeId = "bosca.profiles.email",
                    visibility = ProfileVisibility.USER,
                    confidence = 100,
                    priority = 1,
                    source = "oauth2",
                    attributes = buildJsonObject { put("email", email) },
                    verified = false,
                )
            )
            // Collision detected via the backstop → pending-link minted, pinning the unverified-attribute profile.
            val challenge = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup(email, OAuth2CredentialAttributes("new-sub", null, null, "google"))
            }
            assertEquals(listOf(LinkProofMethod.EMAIL), challenge.methods)

            // The proof email is delivered to the pinned profile rather than failing.
            service.requestAccountLinkEmailProof(challenge.token)
            assertEquals(setOf(deliveryProfileId), accountLinkEmailRequests.single().recipientIds)
        }
    }

    @Test
    fun `requestAccountLinkEmailProof is rate limited per target to prevent inbox flooding`() = runTest {
        withRequestCache {
            val pendingCache = InMemoryStringCache()
            val rateCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any()) } returns pendingCache
            // The AuthRateLimiter uses its own cache name; route everything else to the rate cache.
            coEvery { cacheManager.maybeAddCache(neq(PendingLinkStore.CACHE_NAME), StringKeySerializer, any()) } returns rateCache

            val targetId = UUID.random()
            val email = "owner@example.com"
            coEvery { profileServiceInstance.getProfilesByEmail(email) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(targetId) } returns Principal(id = targetId, verified = true, anonymous = false)
            // OAuth-only account → email is the only proof method.
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returns listOf(
                PrincipalCredential(principal = targetId, attributes = OAuth2CredentialAttributes("existing-sub", null, null, "google"))
            )
            coEvery { profileServiceInstance.getByPrincipal(targetId) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            val challenge = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup(email, OAuth2CredentialAttributes("new-sub", null, null, "google"))
            }

            // 5 proof emails are allowed within the window; the 6th is rejected, so a held token cannot
            // be looped to flood the target's inbox.
            repeat(5) { service.requestAccountLinkEmailProof(challenge.token) }
            assertFailsWith<SecurityException> { service.requestAccountLinkEmailProof(challenge.token) }
            assertEquals(5, accountLinkEmailRequests.size)
        }
    }

    @Test
    fun `assertEmailChangeAllowed is rate limited to one change per window per principal`() = runTest {
        withRequestCache {
            // Route the email-change rate limiter to a real in-memory cache so the counter actually ticks.
            val rateCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache("auth:rate-limit:email-change", StringKeySerializer, any()) } returns rateCache

            val principalId = UUID.random()
            // Guard passes: the new addresses aren't anyone else's verified email.
            coEvery { profileServiceInstance.getProfilesByEmail(any()) } returns emptyList()

            // The first change in the window is allowed...
            service.assertEmailChangeAllowed(principalId, "new@example.com")
            // ...a second within the window is rejected.
            assertFailsWith<SecurityException> {
                service.assertEmailChangeAllowed(principalId, "newer@example.com")
            }
        }
    }

    @Test
    fun `linkCredentialToPrincipal is idempotent when the identifier already belongs to the same principal`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val existing = PrincipalCredential(principal = principalId, attributes = OAuth2CredentialAttributes("sub-xyz", null, null, "google"))
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("sub-xyz", any()) } returns principalId
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(existing)

            val result = service.linkCredentialToPrincipal(principalId, OAuth2CredentialAttributes("sub-xyz", null, null, "google"))

            // Already owned by this principal → no-op: returns existing, does not insert or bump.
            assertEquals(principalId, result.principal)
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
            coVerify(exactly = 0) { principalRepository.incrementTokenVersion(any()) }
        }
    }

    @Test
    fun `linkCredentialToPrincipal handles case-folded fallback inconsistent lookup and missing principal`() = runTest {
        withRequestCache {
            val fallbackId = UUID.random()
            val fallback = PrincipalCredential(
                principal = fallbackId,
                attributes = OAuth2CredentialAttributes("stored-subject", null, null, "google"),
            )
            coEvery {
                principalRepository.getPrincipalById(fallbackId)
            } returns Principal(id = fallbackId, anonymous = false)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("requested-subject", any())
            } returns fallbackId
            coEvery { credentialsRepository.getByPrincipalId(fallbackId) } returns listOf(
                PrincipalCredential(
                    principal = fallbackId,
                    attributes = CredentialPasswordAttributes("unrelated", pwHash("hash")),
                ),
                fallback,
            )

            assertEquals(
                fallback,
                service.linkCredentialToPrincipal(
                    fallbackId,
                    OAuth2CredentialAttributes("requested-subject", null, null, "google"),
                ),
            )

            val inconsistentId = UUID.random()
            coEvery {
                principalRepository.getPrincipalById(inconsistentId)
            } returns Principal(id = inconsistentId, anonymous = false)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("missing-subject", any())
            } returns inconsistentId
            coEvery { credentialsRepository.getByPrincipalId(inconsistentId) } returns emptyList()
            assertFailsWith<IllegalStateException> {
                service.linkCredentialToPrincipal(
                    inconsistentId,
                    OAuth2CredentialAttributes("missing-subject", null, null, "google"),
                )
            }

            val missingId = UUID.random()
            coEvery { principalRepository.getPrincipalById(missingId) } returns null
            assertFailsWith<IllegalStateException> {
                service.linkCredentialToPrincipal(
                    missingId,
                    OAuth2CredentialAttributes("new-subject", null, null, "google"),
                )
            }
        }
    }

    @Test
    fun `confirmAccountLinkWithPassword re-auths against the account's actual password identifier, not the email`() = runTest {
        withRequestCache {
            val fakeCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any()) } returns fakeCache

            val targetId = UUID.random()
            val target = Principal(id = targetId, verified = true, anonymous = false)
            val email = "owner@example.com"
            // The account's password login identifier differs from its profile email.
            val loginIdentifier = "alt-login@example.com"
            val passwordCredential = PrincipalCredential(principal = targetId, attributes = CredentialPasswordAttributes(loginIdentifier, pwHash("stored-hash")))

            coEvery { profileServiceInstance.getProfilesByEmail(email) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(targetId) } returns target
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returns listOf(passwordCredential)
            // authenticateWithCredential looks the password credential up by its real identifier:
            coEvery { credentialsRepository.getByIdentifier(loginIdentifier, CredentialType.PASSWORD) } returns listOf(passwordCredential)
            coEvery { argonPasswordEncoder.matches("correct-password", any()) } returns true
            coEvery { principalGroupsRepository.getPrincipalGroups(targetId) } returns emptyList()
            // attach (the pending OAuth credential) + login:
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new-sub", any()) } returns null
            coEvery { credentialsRepository.add(any()) } returns mockk()
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()

            // Collision mints a pending link (OAuth method to attach).
            val challenge = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup(email, OAuth2CredentialAttributes("new-sub", null, null, "google"))
            }

            // Proof by the correct password succeeds even though the credential identifier != the email.
            val response = service.confirmAccountLinkWithPassword(challenge.token, "correct-password")

            assertEquals(targetId, response.principalId)
            coVerify { credentialsRepository.getByIdentifier(loginIdentifier, CredentialType.PASSWORD) }
            coVerify { credentialsRepository.add(match { it.principal == targetId && it.type == CredentialType.OAUTH2 }) }
        }
    }

    @Test
    fun `confirmAccountLinkWithPassword is rate limited per target to blunt password brute-force`() = runTest {
        withRequestCache {
            val pendingCache = InMemoryStringCache()
            val rateCache = InMemoryStringCache()
            coEvery { cacheManager.maybeAddCache(PendingLinkStore.CACHE_NAME, StringKeySerializer, any()) } returns pendingCache
            // The link-password AuthRateLimiter uses its own cache name; route everything non-pending to it so
            // the counter actually persists across attempts.
            coEvery { cacheManager.maybeAddCache(neq(PendingLinkStore.CACHE_NAME), StringKeySerializer, any()) } returns rateCache

            val targetId = UUID.random()
            val target = Principal(id = targetId, verified = true, anonymous = false)
            val email = "owner@example.com"
            val loginIdentifier = "owner@example.com"
            val passwordCredential = PrincipalCredential(principal = targetId, attributes = CredentialPasswordAttributes(loginIdentifier, pwHash("stored-hash")))

            coEvery { profileServiceInstance.getProfilesByEmail(email) } returns listOf(
                Profile(id = UUID.random(), principal = targetId, name = "Owner", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { principalRepository.getPrincipalById(targetId) } returns target
            coEvery { credentialsRepository.getByPrincipalId(targetId) } returns listOf(passwordCredential)
            coEvery { credentialsRepository.getByIdentifier(loginIdentifier, CredentialType.PASSWORD) } returns listOf(passwordCredential)
            // Every guess is WRONG → loginWithCredential throws InvalidPassword (a SecurityException).
            coEvery { argonPasswordEncoder.matches(any(), any()) } returns false

            // A collision mints a pending link (a new OAuth identity to attach onto the password account).
            val challenge = assertFailsWith<AccountLinkRequired> {
                service.verifyEmailAvailableForSignup(email, OAuth2CredentialAttributes("new-sub", null, null, "google"))
            }

            // 5 wrong guesses are each rejected as invalid credentials...
            repeat(5) { i ->
                assertFailsWith<SecurityException> { service.confirmAccountLinkWithPassword(challenge.token, "wrong-$i") }
            }
            // ...the 6th is blocked by the rate limiter BEFORE re-auth — a held token (obtainable by attempting a
            // sign-up with the victim's verified email) is not an unbounded password-guessing oracle.
            val limited = assertFailsWith<SecurityException> {
                service.confirmAccountLinkWithPassword(challenge.token, "wrong-6")
            }
            assertEquals("link.password.rate.limited", limited.message)
            // No credential was ever attached — the cap, not token exhaustion, is what stops the brute-force
            // (peekPendingLink never consumes on a failed attempt, so the token itself stays live).
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `linkCredentialToPrincipal rejects attaching a second password credential`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            coEvery { principalRepository.getPrincipalById(principalId) } returns Principal(id = principalId, anonymous = false)
            // The new password identifier is owned by no one yet...
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType("new-pass@example.com", any()) } returns null
            // ...but the principal already has a password credential — at most one is allowed per account.
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(
                PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("existing@example.com", pwHash("h")))
            )

            assertFailsWith<CredentialConflict> {
                service.linkCredentialToPrincipal(principalId, CredentialPasswordAttributes("new-pass@example.com", pwHash("h2")))
            }
            // The second password is never inserted.
            coVerify(exactly = 0) { credentialsRepository.add(any()) }
        }
    }

    @Test
    fun `findDuplicateAccounts groups rows by email`() = runTest {
        withRequestCache {
            val a = UUID.random()
            val b = UUID.random()
            coEvery { principalRepository.findDuplicateVerifiedEmailAccounts() } returns listOf(
                bosca.security.repository.DuplicatedAccount("dup@example.com", a),
                bosca.security.repository.DuplicatedAccount("dup@example.com", b),
            )

            val groups = service.findDuplicateAccounts()

            assertEquals(1, groups.size)
            assertEquals("dup@example.com", groups[0].email)
            assertEquals(setOf(a, b), groups[0].principalIds.toSet())
        }
    }

    @Test
    fun `mergePrincipals rejects merging a principal into itself`() = runTest {
        withRequestCache {
            val id = UUID.random()
            assertFailsWith<SecurityException> { service.mergePrincipals(id, id) }
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
        }
    }

    @Test
    fun `mergePrincipals moves credentials and profiles, then retires the duplicate`() = runTest {
        withRequestCache {
            val survivorId = UUID.random()
            val duplicateId = UUID.random()
            // Survivor already has a primary profile, so step 4 (assign primary) is skipped.
            val survivor = Principal(id = survivorId, verified = true, anonymous = false, primaryProfileId = UUID.random())
            val duplicate = Principal(id = duplicateId, verified = true, anonymous = false)

            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(
                PrincipalCredential(principal = duplicateId, attributes = OAuth2CredentialAttributes("dup-sub", null, null, "google")),
                PrincipalCredential(principal = duplicateId, attributes = CredentialPasswordAttributes("dup@example.com", pwHash("hash"))),
            )
            // After the merge, registerVerifiedPrincipalEmails re-reads the survivor's VERIFIED email
            // attributes (via getByPrincipal, which defaults to empty here, so nothing new is claimed).
            coEvery { credentialsRepository.getByPrincipalId(survivorId) } returns emptyList()
            // One identifier is associated with a third principal and one has no current owner. Neither belongs
            // to the survivor, so both duplicate credentials still move.
            val otherOwnerId = UUID.random()
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType(any(), any())
            } answers {
                if (firstArg<String>() == "dup-sub") otherOwnerId else null
            }
            coEvery { principalRepository.getPrincipalById(otherOwnerId) } returns
                Principal(id = otherOwnerId, verified = true)
            coEvery { credentialsRepository.update(any()) } returns
                PrincipalCredential(principal = survivorId, attributes = OAuth2CredentialAttributes("dup-sub", null, null, "google"))
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns listOf(
                Profile(id = UUID.random(), principal = duplicateId, name = "Dup", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            )
            coEvery { profileServiceInstance.setPrincipal(any(), survivorId) } returns
                Profile(id = UUID.random(), principal = survivorId, name = "Dup", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            val sharedGroup = Group(id = UUID.random(), name = "shared", description = "d", type = GroupType.PRINCIPAL)
            val dupPersonal = Group(id = UUID.random(), name = "$duplicateId.user", description = "d", type = GroupType.PRINCIPAL)
            coEvery { principalGroupsRepository.getPrincipalGroups(survivorId) } returns emptyList()
            coEvery { principalGroupsRepository.getPrincipalGroups(duplicateId) } returns listOf(sharedGroup, dupPersonal)
            coEvery { principalGroupsRepository.add(any()) } returns Unit
            coEvery { principalGroupsRepository.deleteByPrincipalAndGroupId(any(), any()) } returns Unit
            val editSlot = slot<Principal>()
            coEvery { principalRepository.edit(capture(editSlot)) } answers { editSlot.captured }

            val result = service.mergePrincipals(survivorId, duplicateId)

            assertEquals(survivorId, result.id)
            // Both credentials re-pointed to the survivor.
            coVerify(exactly = 2) { credentialsRepository.update(match { it.principal == survivorId }) }
            // Duplicate's profile re-parented.
            coVerify { profileServiceInstance.setPrincipal(any(), survivorId) }
            // Shared group moved to the survivor; the duplicate's personal group is NOT.
            coVerify(exactly = 1) { principalGroupsRepository.add(any()) }
            coVerify(exactly = 2) { principalGroupsRepository.deleteByPrincipalAndGroupId(duplicateId, any()) }
            // Duplicate retired (unverified + anonymous) and its sessions invalidated.
            coVerify { principalRepository.incrementTokenVersion(duplicateId) }
            assertEquals(false, editSlot.captured.verified)
            assertEquals(true, editSlot.captured.anonymous)
            // the duplicate's verified-email registry entries are dropped during the merge.
            coVerify { principalEmailRepository.deleteByPrincipal(duplicateId) }
        }
    }

    @Test
    fun `mergePrincipals assigns a primary profile and skips existing credentials and groups`() = runTest {
        withRequestCache {
            val survivorId = UUID.random()
            val duplicateId = UUID.random()
            val survivor = Principal(id = survivorId, verified = true, anonymous = false)
            val duplicate = Principal(
                id = duplicateId,
                verified = true,
                anonymous = false,
                attributes = buildJsonObject { put("legacy", true) },
            )
            val duplicateCredential = PrincipalCredential(
                principal = duplicateId,
                attributes = OAuth2CredentialAttributes("shared-sub", null, null, "google"),
            )
            val profile = Profile(
                id = UUID.random(),
                principal = survivorId,
                name = "Survivor",
                type = ProfileType.GENERIC,
                visibility = ProfileVisibility.PUBLIC,
            )
            val existingGroup = Group(UUID.random(), "existing", "Existing", GroupType.SYSTEM)
            val duplicatePersonal = Group(
                UUID.random(),
                "$duplicateId.user",
                "Personal",
                GroupType.PRINCIPAL,
            )
            coEvery { principalRepository.getPrincipalById(survivorId) } returns survivor
            coEvery { principalRepository.getPrincipalById(duplicateId) } returns duplicate
            coEvery { credentialsRepository.getByPrincipalId(duplicateId) } returns listOf(duplicateCredential)
            coEvery {
                principalRepository.getPrincipalIdByIdentifierAndType("shared-sub", CredentialType.OAUTH2)
            } returns survivorId
            coEvery { profileServiceInstance.getByPrincipal(duplicateId) } returns emptyList()
            coEvery { profileServiceInstance.getByPrincipal(survivorId) } returns listOf(profile)
            coEvery { profileServiceInstance.getById(profile.id) } returns profile
            coEvery { principalGroupsRepository.getPrincipalGroups(survivorId) } returns listOf(existingGroup)
            coEvery {
                principalGroupsRepository.getPrincipalGroups(duplicateId)
            } returns listOf(existingGroup, duplicatePersonal)
            coEvery { principalGroupsRepository.deleteByPrincipalAndGroupId(any(), any()) } returns Unit
            coEvery { principalRepository.edit(any()) } answers { firstArg() }

            val result = service.mergePrincipals(survivorId, duplicateId)

            assertEquals(survivorId, result.id)
            coVerify(exactly = 0) { credentialsRepository.update(any()) }
            coVerify(exactly = 0) { principalGroupsRepository.add(any()) }
            coVerify {
                principalRepository.edit(match { it.id == survivorId && it.primaryProfileId == profile.id })
            }
        }
    }

    @Test
    fun `deleteCredential enforces ownership matching and the last-credential invariant`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val password = PrincipalCredential(
                principal = principalId,
                attributes = CredentialPasswordAttributes("Owner@Example.com", pwHash("hash")),
            )
            val oauth = PrincipalCredential(
                principal = principalId,
                attributes = OAuth2CredentialAttributes("subject", null, null, "google"),
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(oauth, password)
            coEvery {
                credentialsRepository.delete(principalId, CredentialType.PASSWORD, "owner@example.com")
            } returns Unit

            service.deleteCredential(principalId, CredentialType.PASSWORD, "owner@example.com")
            coVerify {
                credentialsRepository.delete(principalId, CredentialType.PASSWORD, "owner@example.com")
            }
        }

        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val oauth = PrincipalCredential(
                principal = principalId,
                attributes = OAuth2CredentialAttributes("subject", null, null, "google"),
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(oauth)

            assertFailsWith<IllegalStateException> {
                service.deleteCredential(principalId, CredentialType.OAUTH2, "missing")
            }
            assertFailsWith<IllegalStateException> {
                service.deleteCredential(principalId, CredentialType.OAUTH2, "subject")
            }
        }
    }

    @Test
    fun `loginWithRefreshToken should fail if principal not found`() = runTest {
        withRequestCache {
            val token = "refresh_token"
            val principalId = UUID.random()

            coEvery { principalRefreshTokensRepository.consumeToken(token) } returns bosca.security.model.RefreshToken(principalId, token)
            coEvery { principalRepository.getPrincipalById(principalId) } returns null

            // The refresh path now throws `SecurityException` for
            // all miss/invalid conditions so the failure flows
            // through the normal 401 path rather than a 500.
            assertFailsWith<SecurityException> {
                service.loginWithRefreshToken(token)
            }
        }
    }

    @Test
    fun `loginWithRefreshToken should fail if token not found`() = runTest {
        withRequestCache {
            val token = "refresh_token"
            coEvery { principalRefreshTokensRepository.consumeToken(token) } returns null

            assertFailsWith<SecurityException> {
                service.loginWithRefreshToken(token)
            }
        }
    }
    
    @Test
    fun `processSignupTokens should handle exceptions gracefully`() = runTest {
        withRequestCache {
             val credential = OAuth2CredentialAttributes("sub123", "google")
             val principalId = UUID.random()
             val principal = Principal(id = principalId, verified = true, anonymous = false)
             
             // Setup valid login
             coEvery { credentialsRepository.getByIdentifier(any(), any()) } returns listOf(
                PrincipalCredential(
                    principal = principalId,
                    attributes = credential
                )
            )
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC))
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns mockk()
            
            // Mock exception in organization service
            coEvery { organizationServiceInstance.addMemberByToken(any(), any()) } throws RuntimeException("Some error")
            
            val signupTokens = listOf(SignupToken(SignupTokenType.ORGANIZATION, "token1"))
            
            // Should not throw
            val response = service.loginWithCredential(credential, true, signupTokens)
            assertNotNull(response)
            
            coVerify { organizationServiceInstance.addMemberByToken("token1", principalId) }
        }
    }
    
    @Test
    fun `forgotPassword should send email if principal found and verified`() = runTest {
        withRequestCache {
            val identifier = "test@example.com"
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)
            val profile = Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC, principal = principalId)
            
            // Mock getPrincipalByIdentifier (using credential lookup - fails first)
            coEvery { credentialsRepository.getByIdentifier(identifier, any()) } returns emptyList()
            coEvery { principalRepository.getPrincipalIdByIdentifier(identifier) } returns null
            
            // Mock getPrincipalByEmail -> profileService.getProfilesByEmail
            coEvery { profileServiceInstance.getProfilesByEmail(identifier) } returns listOf(profile)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            
            // Mock editPrincipal
            coEvery { principalRepository.edit(any()) } returns principal.copy(verificationToken = "token")
            
            // Mock profile lookup for the email pipeline event.
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            
            service.forgotPassword(identifier)
            
            coVerify { principalRepository.edit(match { it.verificationToken != null }) }
            assertEquals(setOf(profile.id), passwordResetEmailRequests.single().recipientIds)
        }
    }
    
    @Test
    fun `resetPassword should update password and clear token`() = runTest {
        withRequestCache {
            val token = "reset_token"
            val password = "new_password"
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false, verificationToken = token)
            val credential = PrincipalCredential(principal = principalId, attributes = CredentialPasswordAttributes("old", pwHash("old"))).copy(id = 123L)
            val profile = Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC, principal = principalId)
            
            coEvery { principalRepository.getByVerificationToken(token) } returns principal
            coEvery { principalRepository.edit(any()) } returns principal.copy(verificationToken = null)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            
            // Mock existing credentials for updatePassword
            coEvery { credentialsRepository.getByPrincipalId(principalId) } returns listOf(credential)
            
            // Mock profile lookup in case updatePassword needs it (if identifier is null/missing)
            coEvery { profileServiceInstance.getByPrincipal(principalId) } returns listOf(profile)
            // Mock duplicate check
            coEvery { principalRepository.getPrincipalIdByIdentifierAndType(any(), any()) } returns null
            
            coEvery { credentialsRepository.update(any()) } returns credential
            coEvery { argonPasswordEncoder.encode(password) } returns ArgonPassword("encoded")
            
            service.resetPassword(token, password)
            
            coVerify {
                principalRepository.edit(match { it.verificationToken == null })
                credentialsRepository.update(match { 
                    it.principal == principalId && 
                    it.type == CredentialType.PASSWORD &&
                    (it.attributes as CredentialPasswordAttributes).password == "encoded"
                })
            }
        }
    }
    
    @Test
    fun `addPrincipal should create principal and credentials`() = runTest {
        withRequestCache {
            val principal = Principal(id = UUID.random(), verified = true, anonymous = false)
            val credential = SimplePasswordAttributes("test", "password")
            val groups = listOf(Group(id = UUID.random(), name = "g", description = "d", type = GroupType.PRINCIPAL))
            
            coEvery { principalRepository.add(any()) } returns principal
            coEvery { credentialsRepository.add(any()) } returns PrincipalCredential(principal = principal.id, attributes = CredentialPasswordAttributes("test", pwHash("encoded"))).copy(id = 123L)
            coEvery { principalGroupsRepository.add(any()) } returns mockk()
            coEvery { groupRepository.add(any()) } returns Group(id = UUID.random(), name = "user_group", description = "desc", type = GroupType.PRINCIPAL)
            coEvery { argonPasswordEncoder.encode("password") } returns ArgonPassword("encoded")
            
            val result = service.addPrincipal(principal, credential, groups, true)
            
            assertNotNull(result)
            assertEquals(principal.id, result.id)
            
            coVerify {
                principalRepository.add(principal)
                credentialsRepository.add(match { 
                    it.principal == principal.id && 
                    it.type == CredentialType.PASSWORD 
                })
                principalGroupsRepository.add(match { it.principal == principal.id && it.groupId == groups[0].id })
            }
        }
    }

    @Test
    fun `addPrincipal rejects a duplicate identifier with CredentialConflict`() = runTest {
        withRequestCache {
            val credential = SimplePasswordAttributes("taken@example.com", "password")
            // Centralized guard: an existing principal already owns this identifier (any credential type).
            coEvery { principalRepository.getPrincipalIdByIdentifier("taken@example.com") } returns UUID.random()
            coEvery { principalRepository.getPrincipalById(any()) } returns Principal(id = UUID.random(), anonymous = false)

            assertFailsWith<CredentialConflict> {
                service.addPrincipal(Principal(anonymous = false), credential, emptyList())
            }
            coVerify(exactly = 0) { principalRepository.add(any()) }
        }
    }

    @Test
    fun `editPrincipal should update principal`() = runTest {
        withRequestCache {
            val principal = Principal(id = UUID.random(), verified = true, anonymous = false)
            coEvery { principalRepository.edit(any()) } returns principal
            
            service.editPrincipal(principal)
            
            coVerify { principalRepository.edit(match { it.id == principal.id }) }
        }
    }
    
    @Test
    fun `createJwtToken should set expiry within expected range`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)

            val beforeSec = System.currentTimeMillis() / 1000
            val jwt = service.createJwtToken(principal, emptyMap())
            val afterSec = System.currentTimeMillis() / 1000

            val expiresAtSec = jwt.expiresAtAsInstant.epochSecond
            val expectedLowerBound = beforeSec + 3600
            val expectedUpperBound = afterSec + 3600

            assertTrue(expiresAtSec >= expectedLowerBound, "Token expiry $expiresAtSec should be >= $expectedLowerBound")
            assertTrue(expiresAtSec <= expectedUpperBound, "Token expiry $expiresAtSec should be <= $expectedUpperBound")

            // Verify it's not wildly in the future (old bug set it thousands of years ahead)
            val maxReasonableSec = afterSec + (2 * 3600) // at most 2 hours from now
            assertTrue(expiresAtSec < maxReasonableSec, "Token expiry should not be unreasonably far in the future")
        }
    }

    @Test
    fun `createJwtToken encodes exp and iat claims that survive re-decode from the signed string`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)

            val beforeSec = System.currentTimeMillis() / 1000
            val jwt = service.createJwtToken(principal, emptyMap())
            val afterSec = System.currentTimeMillis() / 1000

            // Re-decode the signed string from scratch — this reads the raw `exp`/`iat` claims
            // as the client would see them after network transport, bypassing any Date/Instant
            // wrapping the in-memory DecodedJWT might do.
            val redecoded = com.auth0.jwt.JWT.decode(jwt.token)

            val expClaim = redecoded.getClaim("exp").asLong()
                ?: error("JWT has no exp claim")
            val iatClaim = redecoded.getClaim("iat").asLong()
                ?: error("JWT has no iat claim")

            println("DEBUG exp=$expClaim iat=$iatClaim lifetime=${expClaim - iatClaim} beforeSec=$beforeSec")

            // iat should be "now" (within the test window)
            assertTrue(iatClaim in beforeSec..afterSec,
                "iat $iatClaim should be in [$beforeSec, $afterSec]")

            // exp should be iat + expirationTimeInSeconds (3600 in this test fixture)
            val lifetime = expClaim - iatClaim
            assertEquals(3600L, lifetime,
                "exp-iat lifetime should equal expirationTimeInSeconds (3600), got $lifetime")

            // And exp should land roughly now+3600
            assertTrue(expClaim in (beforeSec + 3600)..(afterSec + 3600),
                "exp $expClaim should be in [${beforeSec + 3600}, ${afterSec + 3600}]")
        }
    }

    @Test
    fun `LoginResponse token sent to the client has correct issuedAt and expiresAt`() = runTest {
        withRequestCache {
            // Exercise the same path a real client-facing login would take:
            //   createJwtToken -> newLoginResponse -> LoginResponse.token (the thing
            //   serialized over GraphQL as `Token { expiresAt, issuedAt, token }`).
            val refreshTokenValue = "refresh_token"
            val principalId = UUID.random()
            val principal = Principal(id = principalId, verified = true, anonymous = false)

            coEvery { principalRefreshTokensRepository.consumeToken(refreshTokenValue) } returns bosca.security.model.RefreshToken(principalId, refreshTokenValue)
            coEvery { principalRepository.getPrincipalById(principalId) } returns principal
            coEvery { principalRefreshTokensRepository.addPrincipalRefreshToken(any(), any(), any(), any(), any()) } returns Unit

            val beforeSec = (System.currentTimeMillis() / 1000).toInt()
            val response = service.loginWithRefreshToken(refreshTokenValue)
            val afterSec = (System.currentTimeMillis() / 1000).toInt()

            val clientToken = response.token
            println("DEBUG client-visible Token: issuedAt=${clientToken.issuedAt} expiresAt=${clientToken.expiresAt} " +
                "lifetime=${clientToken.expiresAt - clientToken.issuedAt} beforeSec=$beforeSec")

            // Neither field should have hit the `?: 0` / fallback branches in newLoginResponse.
            assertTrue(clientToken.issuedAt != 0,
                "issuedAt must not be 0 — would mean the fallback branch was taken and the real claim was lost")
            assertTrue(clientToken.expiresAt != 0,
                "expiresAt must not be 0 — would mean the JWT had no exp claim and we silently stamped 1970")

            // issuedAt should be "now" at the moment of login.
            assertTrue(clientToken.issuedAt in beforeSec..afterSec,
                "issuedAt ${clientToken.issuedAt} should be in [$beforeSec, $afterSec]")

            // expiresAt should be now + configured lifetime (3600 in this fixture).
            assertTrue(clientToken.expiresAt in (beforeSec + 3600)..(afterSec + 3600),
                "expiresAt ${clientToken.expiresAt} should be in [${beforeSec + 3600}, ${afterSec + 3600}]")

            // And the two must be internally consistent with the configured TTL.
            assertEquals(3600, clientToken.expiresAt - clientToken.issuedAt,
                "expiresAt - issuedAt should equal expirationTimeInSeconds (3600)")

            // Finally, the values the client sees must match the raw JWT claims byte-for-byte
            // (i.e. newLoginResponse must not rewrite or drift from what createJwtToken produced).
            val redecoded = com.auth0.jwt.JWT.decode(clientToken.token)
            assertEquals(redecoded.getClaim("iat").asLong(), clientToken.issuedAt.toLong(),
                "Client-visible issuedAt must match the JWT's iat claim exactly")
            assertEquals(redecoded.getClaim("exp").asLong(), clientToken.expiresAt.toLong(),
                "Client-visible expiresAt must match the JWT's exp claim exactly")
        }
    }

    @Test
    fun `getPrincipalById should return principal`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val principal = Principal(id = id, verified = true, anonymous = false)
            coEvery { principalRepository.getPrincipalById(id) } returns principal
            
            val result = service.getPrincipalById(id)

            assertEquals(principal, result)
        }
    }
}

/** Minimal in-memory [Cache] used to exercise the pending-link store round-trip in tests. */
private class InMemoryStringCache : Cache<String> {
    private val map = mutableMapOf<String, String?>()
    override val keySerializer: CacheKeySerializer<String> = StringKeySerializer
    override val estimatedSize: Long get() = map.size.toLong()

    private fun rk(key: CacheKey<String>) = key.toRemoteKey()
    private fun cv(v: String?, e: Boolean) = object : CacheValue {
        override val value = v
        override val exists = e
    }

    override suspend fun get(key: CacheKey<String>): CacheValue {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map[k], true) else cv(null, false)
    }

    override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

    override suspend fun put(key: CacheKey<String>, value: String?) {
        map[rk(key)] = value
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
        entries.forEach { put(it.first, it.second) }
    }

    override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map.remove(k), true) else null
    }

    override suspend fun clear() {
        map.clear()
    }

    override suspend fun evictExpiredItems() {}
}
