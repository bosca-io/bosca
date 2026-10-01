package bosca.security.graphql

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.community.service.CommunityService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.asProvider
import bosca.di.provides
import bosca.profile.model.ProfileType
import bosca.profile.model.Profile
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.*
import bosca.security.service.SecurityService
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.serialization.JsonConverter.toJsonElement
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.security.service.AccountLinkRequired
import bosca.security.service.CredentialConflict
import bosca.security.service.ThirdPartyType
import java.util.Locale

@OptIn(InternalDI::class)
class SignupMutationControllerTest {

    private val securityService = mockk<SecurityService>()
    private val profileService = mockk<ProfileService>()
    private val application = mockk<BoscaApplication>()
    private val config = mockk<ApplicationConfig>()
    private val communityService = mockk<CommunityService>()
    private val organizationService = mockk<OrganizationService>()
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val call = mockk<ServerCall> { every { request.appOrigin } returns "https://studio.test" }

    private lateinit var controller: SignupMutationController

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json.Default }

        every { application.environment } returns mockk {
            every { config } returns this@SignupMutationControllerTest.config
        }
        every { config.propertyOrNull("security.autoVerify") } returns null
        coEvery { securityService.sendWelcomeMessage(any()) } returns Unit

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }

        controller = SignupMutationController(
            securityService,
            profileService,
            organizationService,
            communityService.asProvider(),
            application,
            cacheManager
        )
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private fun stubSuccessfulPasswordSignup(): Principal {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authenticatedPrincipal.asPrincipal() } returns principal
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.getPrincipalByIdentifier(any<String>()) } returns null
        coEvery { securityService.addPrincipal(any(), any(), any()) } returns authenticatedPrincipal
        coEvery { profileService.add(any(), any(), any()) } returns mockk<Profile> {
            every { id } returns UUID.random()
        }
        coEvery { securityService.sendVerificationEmail(any(), any()) } returns Unit
        return principal
    }

    private val profileInput = ProfileInput(name = "Test User", visibility = ProfileVisibility.PUBLIC)

    // --- deprecated `password` (returns a bare Principal; throws on collision) ---

    @Test
    fun `deprecated password signup returns the created principal`() = runTest {
        val principal = stubSuccessfulPasswordSignup()

        val result: Principal = controller.password("user@example.com", "password", profileInput, null, null, call)

        assertEquals(principal, result)
        coVerify {
            // The profile is created with a server-ensured `bosca.profiles.email` attribute equal to the
            // signup email, so verification has an attribute to attach its token to.
            profileService.add(
                match { input ->
                    input.name == "Test User" &&
                        input.attributes.any { it.typeId == "bosca.profiles.email" }
                },
                ProfileType.GENERIC,
                principal.id,
            )
            securityService.sendVerificationEmail(principal.id, any())
            securityService.sendWelcomeMessage(any())
        }
    }

    @Test
    fun `deprecated password signup throws when a verified account owns the email`() = runTest {
        coEvery { securityService.verifyEmailAvailableForSignup("taken@example.com", any()) } throws
            AccountLinkRequired("taken@example.com", UUID.random(), "pending-token", listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL))

        // The bare-Principal field cannot express the challenge, so the collision surfaces as an error.
        assertFailsWith<AccountLinkRequired> {
            controller.password("taken@example.com", "password", profileInput, null, null, call)
        }
        coVerify(exactly = 0) { securityService.addPrincipal(any(), any(), any()) }
    }

    // --- `passwordV2` (returns a SignupResult that can carry the link challenge) ---

    @Test
    fun `passwordV2 signup returns the created principal`() = runTest {
        val principal = stubSuccessfulPasswordSignup()

        val result = controller.passwordV2("user@example.com", "password", profileInput, null, null, null, call)

        assertEquals(principal, result.principal)
        assertNull(result.linkChallenge)
    }

    @Test
    fun `passwordV2 stamps the caller-supplied originator on the new credential`() = runTest {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authenticatedPrincipal.asPrincipal() } returns principal
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.getPrincipalByIdentifier(any<String>()) } returns null
        coEvery { securityService.addPrincipal(any(), any(), any(), any(), any()) } returns authenticatedPrincipal
        coEvery { profileService.add(any(), any(), any()) } returns mockk<Profile> {
            every { id } returns UUID.random()
        }
        coEvery { securityService.sendVerificationEmail(any(), any()) } returns Unit

        controller.passwordV2("user@example.com", "password", profileInput, null, null, "studio", call)

        coVerify { securityService.addPrincipal(any(), any(), any(), any(), originator = "studio") }
    }

    @Test
    fun `passwordV2 returns a link challenge when a verified account owns the email`() = runTest {
        coEvery { securityService.verifyEmailAvailableForSignup("taken@example.com", any()) } throws
            AccountLinkRequired("taken@example.com", UUID.random(), "pending-token", listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL))

        val result = controller.passwordV2("taken@example.com", "password", profileInput, null, null, null, call)

        // No principal created; the client gets the challenge to drive security.link.*.
        assertNull(result.principal)
        assertNotNull(result.linkChallenge)
        assertEquals("pending-token", result.linkChallenge?.token)
        assertEquals(listOf(LinkProofMethod.PASSWORD, LinkProofMethod.EMAIL), result.linkChallenge?.methods)
        coVerify(exactly = 0) { securityService.addPrincipal(any(), any(), any()) }
    }

    @Test
    fun `passwordV2 propagates a CredentialConflict from addPrincipal (duplicate identifier)`() = runTest {
        // The duplicate-identifier guard is centralized in the service (addPrincipal), shared by every
        // entry point — including the UNVERIFIED-account case that requireEmailAvailableForSignup (which
        // only resolves *verified* accounts) doesn't catch. The controller just lets it propagate.
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.addPrincipal(any(), any(), any()) } throws CredentialConflict()

        assertFailsWith<CredentialConflict> {
            controller.passwordV2("member@example.com", "password", profileInput, null, null, null, call)
        }
    }

    // --- deprecated `thirdparty` (returns a bare LoginResponse) + `thirdpartyV2` ---

    @Test
    fun `deprecated thirdparty signup returns the login response`() = runTest {
        val loginResponse = LoginResponse(UUID.random(), null, Token(0, 0, "token"))
        coEvery { securityService.loginWithThirdPartyToken(any(), any(), any(), any(), any(), any()) } returns loginResponse

        val result: LoginResponse = controller.thirdparty(ThirdPartyType.GOOGLE, "token", null, null, call)

        assertEquals(loginResponse, result)
        coVerify {
            securityService.loginWithThirdPartyToken(
                type = ThirdPartyType.GOOGLE,
                token = "token",
                locale = any(),
                generateRefreshToken = true,
                signupTokens = emptyList(),
                requestOrigin = "https://studio.test"
            )
        }
    }

    @Test
    fun `thirdpartyV2 returns the login response`() = runTest {
        val loginResponse = LoginResponse(UUID.random(), null, Token(0, 0, "token"))
        coEvery { securityService.loginWithThirdPartyToken(any(), any(), any(), any(), any(), any()) } returns loginResponse

        val result = controller.thirdpartyV2(ThirdPartyType.GOOGLE, "token", null, null, null, call)

        assertEquals(loginResponse, result.loginResponse)
        assertNull(result.linkChallenge)
    }

    @Test
    fun `thirdpartyV2 returns a link challenge when the verified email belongs to another account`() = runTest {
        coEvery { securityService.loginWithThirdPartyToken(any(), any(), any(), any(), any(), any()) } throws
            AccountLinkRequired("taken@example.com", UUID.random(), "pending-token", listOf(LinkProofMethod.EMAIL))

        val result = controller.thirdpartyV2(ThirdPartyType.GOOGLE, "token", null, null, null, call)

        assertNull(result.loginResponse)
        assertNotNull(result.linkChallenge)
        assertEquals("pending-token", result.linkChallenge?.token)
        assertEquals(listOf(LinkProofMethod.EMAIL), result.linkChallenge?.methods)
    }

    @Test
    fun `password signup validates length and normalizes profile attributes`() = runTest {
        val principal = stubSuccessfulPasswordSignup()
        val suppliedEmail = ProfileAttributeInput(
            typeId = "bosca.profiles.email",
            attributes = mapOf("email" to "wrong@example.com").toJsonElement(),
            priority = 1,
            source = "test",
            confidence = 100,
            visibility = ProfileVisibility.USER,
        )
        val suppliedLocale = ProfileAttributeInput(
            typeId = "bosca.profiles.locale",
            attributes = mapOf("locale" to "en").toJsonElement(),
            priority = 1,
            source = "test",
            confidence = 100,
            visibility = ProfileVisibility.USER,
        )
        val input = profileInput.copy(attributes = listOf(suppliedEmail, suppliedLocale))
        coEvery { organizationService.addMemberByEmail("User@Example.com", principal.id) } returns Unit

        controller.passwordV2(
            "User@Example.com",
            "valid-password",
            input,
            "fr-CA",
            emptyList(),
            null,
            call,
        )

        coVerify {
            profileService.add(
                match {
                    it.attributes.count { attribute -> attribute.typeId == "bosca.profiles.email" } == 1 &&
                        it.attributes.single { attribute -> attribute.typeId == "bosca.profiles.locale" } === suppliedLocale
                },
                ProfileType.GENERIC,
                principal.id,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.passwordV2("new@example.com", "short", profileInput, null, null, null, call)
        }
        assertFailsWith<IllegalArgumentException> {
            controller.passwordV2("new@example.com", "x".repeat(129), profileInput, null, null, null, call)
        }
    }

    @Test
    fun `password signup adds locale but does not invent email for a non-email identifier`() = runTest {
        val principal = stubSuccessfulPasswordSignup()

        controller.passwordV2("username", "valid-password", profileInput, "fr-CA", null, null, call)

        coVerify {
            profileService.add(
                match {
                    it.attributes.none { attribute -> attribute.typeId == "bosca.profiles.email" } &&
                        it.attributes.single { attribute -> attribute.typeId == "bosca.profiles.locale" }
                            .attributes.toString().contains("fr-CA")
                },
                ProfileType.GENERIC,
                principal.id,
            )
        }
    }

    @Test
    fun `auto verified signup skips verification email`() = runTest {
        val autoVerifyConfig = ApplicationConfig.load(
            "security:\n  autoVerify: true\n".byteInputStream()
        )
        val autoVerifyApplication = mockk<BoscaApplication> {
            every { environment } returns mockk {
                every { config } returns autoVerifyConfig
            }
        }
        val autoVerifiedPrincipal = Principal(id = UUID.random(), verified = true, anonymous = false)
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.addPrincipal(any(), any(), any()) } returns
            AuthenticatedPrincipal(autoVerifiedPrincipal, emptyList())
        coEvery { profileService.add(any(), any(), any()) } returns mockk<Profile> {
            every { id } returns UUID.random()
        }
        val autoVerifyController = SignupMutationController(
            securityService,
            profileService,
            organizationService,
            communityService.asProvider(),
            autoVerifyApplication,
            mockk(relaxed = true),
        )

        val result = autoVerifyController.passwordV2(
            "verified@example.com",
            "valid-password",
            profileInput,
            null,
            null,
            null,
            call,
        )

        assertEquals(autoVerifiedPrincipal, result.principal)
        coVerify(exactly = 0) { securityService.sendVerificationEmail(autoVerifiedPrincipal.id, any()) }
    }

    @Test
    fun `third party signup passes explicit locale tokens and originator`() = runTest {
        val response = LoginResponse(UUID.random(), null, Token(0, 0, "token"), originator = "studio")
        val tokens = listOf(SignupToken(SignupTokenType.ORGANIZATION, "invite"))
        coEvery {
            securityService.loginWithThirdPartyToken(
                ThirdPartyType.APPLE,
                "provider-token",
                Locale.forLanguageTag("fr-CA"),
                true,
                tokens,
                "https://studio.test",
                "studio",
            )
        } returns response

        assertEquals(
            response,
            controller.thirdpartyV2(
                ThirdPartyType.APPLE,
                "provider-token",
                tokens,
                "fr-CA",
                "studio",
                call,
            ).loginResponse,
        )
    }

    @Test
    fun `password verification supports success legacy behavior and link challenges`() = runTest {
        coEvery { securityService.verifyWithToken("valid") } returns Unit
        coEvery { securityService.verifyWithToken("link") } throws AccountLinkRequired(
            "person@example.com",
            UUID.random(),
            "pending",
            listOf(LinkProofMethod.EMAIL),
        )

        assertEquals(true, controller.passwordVerify("valid"))
        val success = controller.passwordVerifyV2("valid")
        assertNull(success.linkChallenge)
        val challenge = controller.passwordVerifyV2("link").linkChallenge
        assertEquals("pending", challenge?.token)
        assertEquals(listOf(LinkProofMethod.EMAIL), challenge?.methods)
        assertFailsWith<AccountLinkRequired> {
            controller.passwordVerify("link")
        }
    }

    @Test
    fun `resend verification is enumeration safe and sends for an existing principal`() = runTest {
        val principal = Principal(id = UUID.random())
        coEvery { securityService.getPrincipalByIdentifier("existing@example.com") } returns principal
        coEvery { securityService.getPrincipalByIdentifier("missing@example.com") } returns null
        coEvery {
            securityService.sendVerificationEmail(principal.id, "https://studio.test")
        } returns Unit

        assertEquals(true, controller.resendPasswordVerification("existing@example.com", call))
        assertEquals(true, controller.resendPasswordVerification("missing@example.com", call))
        coVerify(exactly = 1) {
            securityService.sendVerificationEmail(principal.id, "https://studio.test")
        }
    }

    @Test
    fun `signup and resend stop at the rate limit`() = runTest {
        val cache = mockk<Cache<String>>()
        val limited = mockk<CacheValue> {
            every { exists } returns true
            every { value } returns "5"
        }
        coEvery { cache.get(any()) } returns limited
        coEvery { cacheManager.maybeAddCache<String>(any(), any(), any()) } returns cache
        val limitedController = SignupMutationController(
            securityService,
            profileService,
            organizationService,
            communityService.asProvider(),
            application,
            cacheManager,
        )

        assertFailsWith<SecurityException> {
            limitedController.passwordV2(
                "limited@example.com",
                "valid-password",
                profileInput,
                null,
                null,
                null,
                call,
            )
        }
        assertEquals(
            true,
            limitedController.resendPasswordVerification("limited@example.com", call),
        )
    }
}
