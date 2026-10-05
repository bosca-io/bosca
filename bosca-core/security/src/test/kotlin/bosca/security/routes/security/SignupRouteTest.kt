package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.community.service.CommunityService
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.LinkProofMethod
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AccountLinkRequired
import bosca.security.service.CredentialConflict
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.LanguageItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class SignupRouteTest {

    private val securityService = mockk<SecurityService>()
    private val profileService = mockk<ProfileService>()
    private val organizationService = mockk<OrganizationService>()
    private val communityService = mockk<CommunityService>()
    private val profileProvider = mockk<ObjectProvider<ProfileService>>()
    private val organizationProvider = mockk<ObjectProvider<OrganizationService>>()
    private val configuration = mockk<SecurityConfiguration> {
        every { allowedRedirects } returns listOf("https://studio.example")
    }
    private val json = Json
    private lateinit var cacheManager: CacheManager
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() {
        cacheManager = authRateLimitCacheManager()
        environment = SecurityRouteTestEnvironment(cacheManager)
        provides<CommunityService> { communityService }
        coEvery { profileProvider.get() } returns profileService
        coEvery { organizationProvider.get() } returns organizationService
        coEvery { securityService.sendWelcomeMessage(any()) } returns Unit
        coEvery { transaction<Profile>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as suspend () -> Profile
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `signup rejects requests without a client address`() = runTest {
        val testCall = jsonCall(clientIp = null)

        route().execute(testCall.call)

        coVerify(exactly = 0) { securityService.addPrincipal(any(), any(), any()) }
        verify {
            testCall.response.respondText(
                match { it.contains("signup.failed") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }

    @Test
    fun `signup rejects weak passwords for json and form redirect flows`() = runTest {
        val route = route()
        val jsonCall = jsonCall(password = "short", clientIp = "192.0.2.11")
        val formCall = formCall(
            password = "x".repeat(129),
            clientIp = "192.0.2.12",
            errorRedirect = "https://studio.example/signup",
        )

        route.execute(jsonCall.call)
        route.execute(formCall.call)

        verify {
            jsonCall.response.respondText(
                match { it.contains("Password must be between") },
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
        verify {
            formCall.response.respondRedirect(
                "https://studio.example/signup?error=signup.password.weak",
                false,
            )
        }
    }

    @Test
    fun `json signup creates profile sends verification and returns profile`() = runTest {
        val testCall = jsonCall(clientIp = "192.0.2.13")
        val principal = Principal(id = UUID.random(), anonymous = false)
        val authenticated = AuthenticatedPrincipal(principal, emptyList())
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principal.id,
            name = "Person",
            visibility = ProfileVisibility.USER,
        )
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery {
            securityService.addPrincipal(
                principal = any(),
                credential = any(),
                groups = emptyList(),
                addPrincipalGroup = true,
                originator = null,
            )
        } returns authenticated
        coEvery {
            profileService.add(any(), any(), principal.id, false)
        } returns profile
        coEvery { securityService.sendVerificationEmail(principal.id, any()) } returns Unit

        route().execute(testCall.call)

        coVerify { securityService.sendVerificationEmail(principal.id, "https://studio.example") }
        coVerify { securityService.sendWelcomeMessage(profile.id) }
        verify {
            testCall.response.respondText(
                any(),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `form signup builds organization details assigns permission groups and redirects`() = runTest {
        val testCall = formCall(
            clientIp = "192.0.2.14",
            redirect = "https://studio.example/welcome",
            organizationName = "Example Church",
        )
        val principal = Principal(id = UUID.random(), anonymous = false)
        val authenticated = AuthenticatedPrincipal(principal, emptyList())
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principal.id,
            name = "Person",
            visibility = ProfileVisibility.USER,
        )
        val organization = mockk<Organization> {
            every { id } returns UUID.random()
        }
        val groupId = UUID.random()
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery {
            securityService.addPrincipal(
                principal = any(),
                credential = any(),
                groups = emptyList(),
                addPrincipalGroup = true,
                originator = null,
            )
        } returns authenticated
        coEvery {
            organizationService.add(any(), any(), isNull())
        } returns organization
        coEvery {
            profileService.add(any(), any(), principal.id, false)
        } returns profile
        val organizationId = organization.id
        coEvery { organizationService.addMember(organizationId, principal.id) } returns Unit
        coEvery {
            organizationService.getPermissions(organization)
        } returns listOf(Permission(groupId, PermissionAction.VIEW))
        coEvery { securityService.addPrincipalGroup(principal.id, groupId) } returns Unit
        coEvery { securityService.sendVerificationEmail(principal.id, any()) } returns Unit

        route().execute(testCall.call)

        coVerify {
            organizationService.add(
                match {
                    it.name == "Example Church" &&
                        it.systemAttributes.toString().contains("tradition")
                },
                match { it.name == "Example Church" },
                null,
            )
            organizationService.addMember(organizationId, principal.id)
            securityService.addPrincipalGroup(principal.id, groupId)
        }
        verify {
            testCall.response.respondRedirect(
                "https://studio.example/welcome",
                false,
            )
        }
    }

    @Test
    fun `form signup applies the requested locale without creating an organization`() = runTest {
        val testCall = SecurityRouteTestCall(
            contentType = "application/x-www-form-urlencoded",
            clientIp = "192.0.2.141",
            languages = listOf(LanguageItem("fr-CA", 1.0f)),
            form = mapOf(
                "email" to "person@example.com",
                "name" to "Person",
                "password" to "valid-password",
            ),
        )
        val principal = Principal(id = UUID.random(), anonymous = false)
        val profile = Profile(
            id = UUID.random(),
            principal = principal.id,
            type = ProfileType.GENERIC,
            name = "Person",
            visibility = ProfileVisibility.USER,
        )
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.addPrincipal(any(), any(), any(), any(), any()) } returns
            AuthenticatedPrincipal(principal, emptyList())
        coEvery { profileService.add(any(), any(), principal.id, false) } returns profile
        coEvery { organizationService.addMemberByEmail(any(), principal.id) } returns Unit
        coEvery { securityService.sendVerificationEmail(principal.id, any()) } returns Unit

        route().execute(testCall.call)

        coVerify {
            profileService.add(
                match { input -> input.attributes.any { it.attributes.toString().contains("fr-CA") } },
                ProfileType.GENERIC,
                principal.id,
                false,
            )
        }
    }

    @Test
    fun `signup with organization data and a token processes the token instead of creating an organization`() = runTest {
        val principal = Principal(id = UUID.random(), anonymous = false)
        val profileInput = ProfileInput(name = "Person", visibility = ProfileVisibility.USER)
        val request = SignupRequest(
            identifier = "person@example.com",
            password = "valid-password",
            profile = profileInput,
            organization = OrganizationInput(
                name = "Example",
                attributes = buildJsonObject {},
                systemAttributes = buildJsonObject {},
                visibility = ProfileVisibility.PUBLIC,
                domains = emptyList(),
                signupEmails = emptyList(),
                signupTokens = emptyList(),
            ),
            tokens = listOf(bosca.security.model.SignupToken(bosca.security.model.SignupTokenType.ORGANIZATION, "invite")),
        )
        val testCall = SecurityRouteTestCall(
            body = json.encodeToString(SignupRequest.serializer(), request),
            clientIp = "192.0.2.142",
        )
        val profile = Profile(
            id = UUID.random(),
            principal = principal.id,
            type = ProfileType.GENERIC,
            name = "Person",
            visibility = ProfileVisibility.USER,
        )
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.addPrincipal(any(), any(), any(), any(), any()) } returns
            AuthenticatedPrincipal(principal, emptyList())
        coEvery { profileService.add(any(), any(), principal.id, false) } returns profile
        coEvery { organizationService.addMemberByToken("invite", principal.id) } returns Unit
        coEvery { securityService.sendVerificationEmail(principal.id, any()) } returns Unit

        route().execute(testCall.call)

        coVerify { organizationService.addMemberByToken("invite", principal.id) }
        coVerify(exactly = 0) { organizationService.add(any(), any(), any()) }
    }

    @Test
    fun `duplicate account errors return conflict for each supported cause`() = runTest {
        val route = route()
        val accountLink = jsonCall(clientIp = "192.0.2.15")
        val credentialConflict = jsonCall(clientIp = "192.0.2.16")
        val sqlConflict = jsonCall(clientIp = "192.0.2.17")
        coEvery {
            securityService.verifyEmailAvailableForSignup(any(), any())
        } throws AccountLinkRequired(
            "person@example.com",
            UUID.random(),
            "link-token",
            listOf(LinkProofMethod.EMAIL),
        ) andThenThrows CredentialConflict() andThenThrows
            IllegalStateException("wrapped", java.sql.SQLException("duplicate", "23505"))

        route.execute(accountLink.call)
        route.execute(credentialConflict.call)
        route.execute(sqlConflict.call)

        listOf(accountLink, credentialConflict, sqlConflict).forEach {
            verify {
                it.response.respondText(
                    match { body -> body.contains("already exists") },
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
            }
        }
    }

    @Test
    fun `generic signup failures use json and redirect error responses`() = runTest {
        val route = route()
        val jsonFailure = jsonCall(clientIp = "192.0.2.18")
        val redirectFailure = formCall(
            clientIp = "192.0.2.19",
            errorRedirect = "https://studio.example/signup",
        )
        coEvery {
            securityService.verifyEmailAvailableForSignup(any(), any())
        } throws IllegalStateException("database")

        route.execute(jsonFailure.call)
        route.execute(redirectFailure.call)

        verify {
            jsonFailure.response.respondText(
                match { it.contains("Signup failed") },
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
        verify {
            redirectFailure.response.respondRedirect(
                "https://studio.example/signup?error=signup.failed",
                false,
            )
        }
    }

    @Test
    fun `signup fails cleanly when profile persistence returns no principal`() = runTest {
        val testCall = jsonCall(clientIp = "192.0.2.181")
        val principal = Principal(id = UUID.random(), anonymous = false)
        coEvery { securityService.verifyEmailAvailableForSignup(any(), any()) } returns Unit
        coEvery { securityService.addPrincipal(any(), any(), any(), any(), any()) } returns
            AuthenticatedPrincipal(principal, emptyList())
        coEvery { profileService.add(any(), any(), principal.id, false) } returns Profile(
            id = UUID.random(),
            principal = null,
            type = ProfileType.GENERIC,
            name = "Person",
            visibility = ProfileVisibility.USER,
        )

        route().execute(testCall.call)

        coVerify(exactly = 0) { securityService.sendVerificationEmail(any(), any()) }
        verify {
            testCall.response.respondText(
                match { it.contains("Signup failed") },
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    @Test
    fun `duplicate form signup uses duplicate redirect code`() = runTest {
        val testCall = formCall(
            clientIp = "192.0.2.20",
            errorRedirect = "https://studio.example/signup",
        )
        coEvery {
            securityService.verifyEmailAvailableForSignup(any(), any())
        } throws CredentialConflict()

        route().execute(testCall.call)

        verify {
            testCall.response.respondRedirect(
                "https://studio.example/signup?error=signup.duplicate",
                false,
            )
        }
    }

    @Test
    fun `sixth failed signup from an address is rate limited`() = runTest {
        val route = route()
        coEvery {
            securityService.verifyEmailAvailableForSignup(any(), any())
        } throws IllegalStateException("database")

        repeat(5) {
            route.execute(jsonCall(clientIp = "198.51.100.55").call)
        }
        val limited = jsonCall(clientIp = "198.51.100.55")
        route.execute(limited.call)

        verify {
            limited.response.respondText(
                match { it.contains("signup.rate.limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
    }

    private fun route() = Signup(
        securityService,
        configuration,
        profileProvider,
        organizationProvider,
        json,
        cacheManager,
    )

    private fun jsonCall(
        password: String = "valid-password",
        clientIp: String?,
    ) = SecurityRouteTestCall(
        body = json.encodeToString(
            SignupRequest(
                identifier = "person@example.com",
                password = password,
                profile = ProfileInput(
                    name = "Person",
                    visibility = ProfileVisibility.USER,
                ),
                organization = null,
            ),
        ),
        clientIp = clientIp,
    )

    private fun formCall(
        password: String = "valid-password",
        clientIp: String,
        redirect: String? = null,
        errorRedirect: String? = null,
        organizationName: String? = null,
    ) = SecurityRouteTestCall(
        contentType = "application/x-www-form-urlencoded",
        clientIp = clientIp,
        form = buildMap {
            put("email", "person@example.com")
            put("name", "Person")
            put("password", password)
            redirect?.let { put("redirect", it) }
            errorRedirect?.let { put("redirect.error", it) }
            organizationName?.let {
                put("organization.name", it)
                put("organization.tradition", "test-tradition")
                put("organization.size", "100")
                put("organization.country", "US")
            }
        },
    )
}
