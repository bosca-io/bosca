package bosca.security.graphql

import bosca.cache.CacheManager
import bosca.community.service.CommunityService
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.organization.service.process
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.LinkChallenge
import bosca.security.model.LoginResponse
import bosca.security.model.Principal
import bosca.security.model.SignupResult
import bosca.security.model.SignupToken
import bosca.security.model.SimplePasswordAttributes
import bosca.security.routes.security.AuthRateLimiter
import bosca.security.routes.security.PASSWORD_LENGTH_RANGE
import bosca.security.service.AccountLinkRequired
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.service.ThirdPartyType
import bosca.serialization.JsonConverter.toJsonElement
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import java.util.*

object SignupMutation

@TypeController
class SignupMutationController(
    private val securityService: SecurityService,
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val communityService: ObjectProvider<CommunityService>,
    application: BoscaApplication,
    cacheManager: CacheManager
) : GraphQLController<SignupMutation> {

    private val autoVerify = application.environment.config.propertyOrNull("security.autoVerify")?.getAs<Boolean>() ?: false
    private val rateLimiter = AuthRateLimiter(cacheManager, maxAttempts = 5)

    /**
     * Deprecated: returns a bare [Principal] and therefore cannot express the account-link challenge —
     * on a verified-email collision it throws [AccountLinkRequired] (surfaced as a GraphQL error) rather
     * than minting a duplicate. New clients should use [passwordV2]. Kept for backward compatibility.
     */
    @Field
    suspend fun password(
        identifier: String,
        password: String,
        profile: ProfileInput,
        languageTag: String?,
        tokens: List<SignupToken>?,
        call: ServerCall
    ): Principal = doPasswordSignup(identifier, password, profile, languageTag, tokens, call.request.appOrigin, originator = null)

    @Field
    suspend fun passwordV2(
        identifier: String,
        password: String,
        profile: ProfileInput,
        languageTag: String?,
        tokens: List<SignupToken>?,
        originator: String?,
        call: ServerCall
    ): SignupResult = try {
        // Stamp the new password credential with where the sign-up came from.
        SignupResult(principal = doPasswordSignup(identifier, password, profile, languageTag, tokens, call.request.appOrigin, originator))
    } catch (e: AccountLinkRequired) {
        // The email already belongs to a verified account; hand the client the proof challenge instead
        // of failing, so it can drive security.link.* to complete the link.
        SignupResult(linkChallenge = LinkChallenge(e.token, e.methods))
    }

    /**
     * Shared password sign-up. Throws [AccountLinkRequired] when the email already belongs to a verified
     * account — the deprecated [password] lets that surface as an error; [passwordV2] turns it into a
     * link challenge.
     */
    private suspend fun doPasswordSignup(
        identifier: String,
        password: String,
        profile: ProfileInput,
        languageTag: String?,
        tokens: List<SignupToken>?,
        requestOrigin: String?,
        originator: String?
    ): Principal {
        if (rateLimiter.isRateLimited("signup:$identifier")) {
            throw SecurityException("signup.rate.limited")
        }
        rateLimiter.recordFailure("signup:$identifier")
        return transaction {
            require(password.length in PASSWORD_LENGTH_RANGE) { "Password must be between ${PASSWORD_LENGTH_RANGE.first} and ${PASSWORD_LENGTH_RANGE.last} characters" }
            // if a verified account already owns this email, route the user into
            // account-linking instead of silently creating a duplicate principal.
            val attributes = SimplePasswordAttributes(identifier, password)
            securityService.verifyEmailAvailableForSignup(identifier, attributes)
            // addPrincipal centralizes the duplicate-identifier guard (a re-signup against an existing
            // UNVERIFIED account that already owns this identifier surfaces as a clean CredentialConflict),
            // shared by the REST and OAuth-create entry points.
            val principal = securityService.addPrincipal(
                Principal(
                    anonymous = false,
                    verified = autoVerify,
                ),
                credential = attributes,
                groups = emptyList(),
                originator = originator
            ).asPrincipal()
            // The profile carries EXACTLY ONE `bosca.profiles.email` attribute, and it is the signup address:
            // verification attaches its one-time token to that attribute, so the account's single verifiable
            // email must match what it signed up with. Drop any client-supplied email attribute(s) and set
            // the signup one, so a client can neither omit it nor smuggle a second/different address in.
            val normalizedSignupEmail = identifier.lowercase().trim()
            var ensuredAttributes = profile.attributes.filterNot { it.typeId == "bosca.profiles.email" }
            if (normalizedSignupEmail.contains("@")) {
                ensuredAttributes = ensuredAttributes + ProfileAttributeInput(
                    typeId = "bosca.profiles.email",
                    attributes = mapOf("email" to identifier).toJsonElement(),
                    priority = 1,
                    source = "signup",
                    confidence = 100,
                    visibility = ProfileVisibility.USER,
                )
            }
            if (languageTag != null && ensuredAttributes.none { it.typeId == "bosca.profiles.locale" }) {
                ensuredAttributes = ensuredAttributes + ProfileAttributeInput(
                    typeId = "bosca.profiles.locale",
                    attributes = mapOf("locale" to languageTag).toJsonElement(),
                    priority = 1,
                    source = "signup",
                    confidence = 100,
                    visibility = ProfileVisibility.USER,
                )
            }
            val finalProfile = profile.copy(attributes = ensuredAttributes)
            val createdProfile = profileService.add(finalProfile, ProfileType.GENERIC, principal.id)
            tokens?.process(finalProfile, principal, organizationService, communityService)
            securityService.sendWelcomeMessage(createdProfile.id)
            if (!principal.verified) securityService.sendVerificationEmail(principal.id, requestOrigin)
            principal
        }
    }

    /**
     * Deprecated: returns a bare [LoginResponse] and cannot express the account-link challenge — on a
     * verified-email collision it throws [AccountLinkRequired]. New clients should use [thirdpartyV2].
     */
    @Field
    suspend fun thirdparty(
        type: ThirdPartyType,
        token: String,
        tokens: List<SignupToken>?,
        languageTag: String?,
        call: ServerCall
    ): LoginResponse = doThirdPartySignup(type, token, tokens, languageTag, call.request.appOrigin, originator = null)

    @Field
    suspend fun thirdpartyV2(
        type: ThirdPartyType,
        token: String,
        tokens: List<SignupToken>?,
        languageTag: String?,
        originator: String?,
        call: ServerCall
    ): SignupResult = try {
        // The originator is echoed back via loginResponse.originator (set by loginWithThirdParty).
        SignupResult(loginResponse = doThirdPartySignup(type, token, tokens, languageTag, call.request.appOrigin, originator))
    } catch (e: AccountLinkRequired) {
        SignupResult(linkChallenge = LinkChallenge(e.token, e.methods))
    }

    private suspend fun doThirdPartySignup(
        type: ThirdPartyType,
        token: String,
        tokens: List<SignupToken>?,
        languageTag: String?,
        requestOrigin: String?,
        originator: String?
    ): LoginResponse = securityService.loginWithThirdPartyToken(
        type = type,
        token = token,
        locale = languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault(),
        generateRefreshToken = true,
        signupTokens = tokens ?: emptyList(),
        // Carry the host the user is on (browser Origin/Referer) so a verification email for an
        // unverified third-party signup routes back to it; the service validates it against the allow-list.
        requestOrigin = requestOrigin,
        // Stamp the new OAuth credential with where the sign-up came from.
        originator = originator
    )

    /**
     * Deprecated: returns a bare [Boolean] and therefore cannot express the account-link challenge — when the
     * proven email is already owned by a verified account it throws [AccountLinkRequired] (surfaced as a
     * GraphQL error) rather than handing back the proof challenge. New clients should use [passwordVerifyV2].
     */
    @Field
    suspend fun passwordVerify(verificationToken: String): Boolean {
        securityService.verifyWithToken(verificationToken)
        return true
    }

    @Field
    suspend fun passwordVerifyV2(verificationToken: String): SignupResult = try {
        securityService.verifyWithToken(verificationToken)
        // Verified, no challenge — the user is not logged in by verifying; the client proceeds to sign in.
        SignupResult()
    } catch (e: AccountLinkRequired) {
        // The proven email is already owned by a verified account. Hand back the proof challenge so the client
        // can complete the link (attach this credential to the owner + retire this duplicate) via security.link.*
        // instead of dead-ending the verification.
        SignupResult(linkChallenge = LinkChallenge(e.token, e.methods))
    }

    @Field
    suspend fun resendPasswordVerification(identifier: String, call: ServerCall): Boolean {
        if (rateLimiter.isRateLimited("resend:$identifier")) {
            return true
        }
        rateLimiter.recordFailure("resend:$identifier")
        val principal = securityService.getPrincipalByIdentifier(identifier)
        if (principal != null) {
            securityService.sendVerificationEmail(principal.id, call.request.appOrigin)
        }
        return true
    }
}
