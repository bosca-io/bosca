package bosca.security.routes.security

import bosca.community.service.CommunityService
import bosca.db.transaction
import bosca.di.provideProvider
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.OrganizationDetails
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.organization.service.process
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.Principal
import bosca.security.service.AccountLinkRequired
import bosca.security.service.AuthenticationContext
import bosca.security.service.CredentialConflict
import bosca.cache.CacheManager
import bosca.di.ObjectProvider
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.serialization.JsonConverter.toJsonElement
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Creates a new user account with credentials, profile, and optional organization.
 *
 * Supports both JSON and form-encoded request bodies. Form requests may include
 * redirect parameters for server-rendered page flows. After successful signup,
 * a verification email is sent to the user.
 */
@RouteController("/api/v1/security/signup", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class Signup(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    private val profileService: ObjectProvider<ProfileService>,
    private val organizationService: ObjectProvider<OrganizationService>,
    private val json: Json,
    cacheManager: CacheManager,
) : Route<Unit>() {

    private val rateLimiter = AuthRateLimiter(cacheManager, maxAttempts = 5)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val clientIp = call.request.clientIp
        if (clientIp == null) {
            log.warn("Signup rejected: unable to determine client IP")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("signup.failed"))
            return
        }
        val rateLimitIdentifier = "signup:$clientIp"
        if (rateLimiter.isRateLimited(rateLimitIdentifier)) {
            log.warn("Signup rate limited for address: {}", clientIp)
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("signup.rate.limited"))
            return
        }
        var redirect: String? = null
        var errorRedirect: String? = null
        val language = call.request.acceptLanguageItems().firstOrNull()?.value
        val locale = Locale.forLanguageTag(language ?: "en-US")
        val signupRequest = if (call.isFormRequest()) {
            val parameters = call.receiveParameters()
            val email: String by parameters
            val name: String by parameters
            val organizationName = parameters.getOrNull<String>("organization.name")
            val organizationTradition = parameters.getOrNull<String>("organization.tradition")
            val organizationSize = parameters.getOrNull<String>("organization.size")
            val organizationCountry = parameters.getOrNull<String>("organization.country")
            val password: String by parameters
            redirect = parameters.getOrNull("redirect")
            errorRedirect = parameters.getOrNull("redirect.error")
            SignupRequest(
                email,
                password,
                ProfileInput(
                    name = name,
                    attributes = listOf(
                        ProfileAttributeInput(
                            typeId = "bosca.profiles.name",
                            attributes = mapOf("name" to name).toJsonElement(),
                            priority = 1,
                            source = "signup",
                            confidence = 100,
                            visibility = ProfileVisibility.USER,
                        ),
                        ProfileAttributeInput(
                            typeId = "bosca.profiles.locale",
                            attributes = mapOf("locale" to locale.toLanguageTag()).toJsonElement(),
                            priority = 1,
                            source = "signup",
                            confidence = 100,
                            visibility = ProfileVisibility.USER,
                        ),
                        ProfileAttributeInput(
                            typeId = "bosca.profiles.email",
                            attributes = mapOf("email" to email).toJsonElement(),
                            priority = 1,
                            source = "signup",
                            confidence = 100,
                            visibility = ProfileVisibility.USER,
                        ),
                    ),
                    visibility = ProfileVisibility.USER,
                ),
                organization = organizationName?.let {
                    OrganizationInput(
                        name = it,
                        attributes = emptyMap<String, String>().toJsonElement(),
                        systemAttributes = json.encodeToJsonElement(
                            OrganizationDetails(
                                tradition = organizationTradition,
                                size = organizationSize,
                                country = organizationCountry,
                                addresses = emptyList(),
                                contacts = emptyList(),
                            )
                        ),
                        visibility = ProfileVisibility.PUBLIC,
                        domains = emptyList(),
                        signupEmails = emptyList(),
                        signupTokens = emptyList(),
                    )
                }
            )
        } else {
            call.receive<SignupRequest>()
        }
        redirect = getFormRedirect(call, redirect, "redirect", securityConfiguration.allowedRedirects)
        errorRedirect = getFormRedirect(call, errorRedirect, "redirect.error", securityConfiguration.allowedRedirects)
        if (signupRequest.password.length !in PASSWORD_LENGTH_RANGE) {
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be between 8 and 128 characters"))
            } else {
                call.respondRedirect("$errorRedirect?error=signup.password.weak")
            }
            return
        }
        try {
            val communityService = provideProvider<CommunityService>()
            val profile = transaction {
                // don't fork a duplicate of an existing verified account; require linking instead.
                val attributes = SimplePasswordAttributes(signupRequest.identifier, signupRequest.password)
                securityService.verifyEmailAvailableForSignup(signupRequest.identifier, attributes)
                val principal = securityService.addPrincipal(
                    Principal(anonymous = false, verified = false),
                    credential = attributes,
                )
                if (signupRequest.organization != null && signupRequest.tokens.isEmpty()) {
                    val organizationService = organizationService.get()
                    val organization = organizationService.add(
                        signupRequest.organization, ProfileInput(
                            name = signupRequest.organization.name,
                            attributes = signupRequest.organization.profileAttributes,
                            visibility = ProfileVisibility.PUBLIC,
                        )
                    )
                    val profileService = profileService.get()
                    val profile = profileService.add(signupRequest.profile, ProfileType.GENERIC, principal.id)
                    organizationService.addMember(organization.id, principal.id)
                    val permissions = organizationService.getPermissions(organization)
                    for (permission in permissions) {
                        securityService.addPrincipalGroup(principal.id, permission.groupId)
                    }
                    profile
                } else {
                    val organizationService = organizationService.get()
                    val profileService = profileService.get()
                    val profile = profileService.add(signupRequest.profile, ProfileType.GENERIC, principal.id)
                    signupRequest.tokens.process(signupRequest.profile, principal.asPrincipal(), organizationService, communityService)
                    profile
                }
            }
            securityService.sendWelcomeMessage(profile.id)
            securityService.sendVerificationEmail(profile.principal ?: error("missing principal"), call.request.appOrigin)
            rateLimiter.recordFailure(rateLimitIdentifier)
            if (redirect != null) {
                call.respondRedirect(redirect)
            } else {
                call.respond(HttpStatusCode.OK, profile)
            }
        } catch (e: Exception) {
            rateLimiter.recordFailure(rateLimitIdentifier)
            // A cross-method collision (signing up with a password for an email that already has a
            // verified OAuth account) surfaces as AccountLinkRequired; an account that already has a
            // password surfaces as CredentialConflict (nothing to link). Both mean "account exists", so
            // treat them like a duplicate rather than a generic failure.
            val isDuplicate = isDuplicateKeyException(e) || e is AccountLinkRequired || e is CredentialConflict
            if (errorRedirect == null) {
                if (isDuplicate) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("An account with this email already exists"))
                } else {
                    log.error("failed to signup", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Signup failed"))
                }
            } else {
                log.error("failed to signup", e)
                if (isDuplicate) {
                    call.respondRedirect("$errorRedirect?error=signup.duplicate")
                } else {
                    call.respondRedirect("$errorRedirect?error=signup.failed")
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Signup::class.java)

        /** Checks if the exception (or its cause chain) is a SQL unique constraint violation (state 23505). */
        private fun isDuplicateKeyException(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
                cause = cause.cause
            }
            return false
        }
    }
}
