package bosca.core.security

import bosca.core.security.model.AuthResponse
import bosca.core.security.model.Group
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import bosca.core.security.type.ThirdPartyType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * [AuthGraphql] backed by the hand-rolled [AuthHttpClient] (Ktor + kotlinx
 * serialization) rather than Apollo.
 * Each method builds a query string + a `variables` object and unwraps the
 * matching response DTO. Bearer tokens are attached per call so refresh/login
 * never recurse through an auto-refreshing client.
 */
class AuthGraphqlImpl(private val http: AuthHttpClient) : AuthGraphql {

    override suspend fun loginWithPassword(identifier: String, password: String): AuthResponse {
        val data = http.execute(
            AuthQueries.LOGIN_PASSWORD,
            buildJsonObject { put("identifier", identifier); put("password", password) },
            LoginEnvelope.serializer(),
        )
        return (data.security.login.password ?: throw GraphQLAuthError("Login returned no response")).toDomain()
    }

    override suspend fun refreshToken(refreshToken: String): AuthResponse {
        val data = http.execute(
            AuthQueries.REFRESH_TOKEN,
            buildJsonObject { put("refreshToken", refreshToken) },
            LoginEnvelope.serializer(),
        )
        return (data.security.login.refreshToken ?: throw GraphQLAuthError("Refresh returned no response")).toDomain()
    }

    override suspend fun exchangeToken(token: String): AuthResponse {
        val data = http.execute(
            AuthQueries.EXCHANGE_TOKEN,
            buildJsonObject { put("token", token) },
            LoginEnvelope.serializer(),
        )
        return (data.security.login.exchangeToken ?: throw GraphQLAuthError("Exchange returned no response")).toDomain()
    }

    override suspend fun signOut(token: String?) {
        http.execute(AuthQueries.SIGN_OUT, emptyVariables(), JsonElement.serializer(), token)
    }

    override suspend fun forgotPassword(identifier: String) {
        http.execute(
            AuthQueries.FORGOT_PASSWORD,
            buildJsonObject { put("identifier", identifier) },
            JsonElement.serializer(),
        )
    }

    override suspend fun resetPassword(token: String, password: String) {
        http.execute(
            AuthQueries.RESET_PASSWORD,
            buildJsonObject { put("token", token); put("password", password) },
            JsonElement.serializer(),
        )
    }

    override suspend fun signupWithPassword(options: SignupOptions): Principal {
        val data = http.execute(
            AuthQueries.SIGNUP_PASSWORD,
            buildJsonObject {
                put("identifier", options.identifier)
                put("password", options.password)
                put("profile", options.profile.toVariable())
                put("languageTag", options.languageTag)
            },
            SignupEnvelope.serializer(),
        )
        return (data.security.signup.password ?: throw GraphQLAuthError("Signup returned no principal")).toDomain()
    }

    override suspend fun signupThirdParty(type: ThirdPartyType, token: String, languageTag: String?): AuthResponse {
        val data = http.execute(
            AuthQueries.SIGN_IN_WITH_THIRD_PARTY,
            buildJsonObject {
                put("type", type.rawValue)
                put("token", token)
                put("languageTag", languageTag)
            },
            SignupEnvelope.serializer(),
        )
        return (data.security.signup.thirdparty ?: throw GraphQLAuthError("Third-party sign-in returned no response")).toDomain()
    }

    override suspend fun verifyEmail(verificationToken: String) {
        http.execute(
            AuthQueries.VERIFY_EMAIL,
            buildJsonObject { put("verificationToken", verificationToken) },
            JsonElement.serializer(),
        )
    }

    override suspend fun resendVerification(identifier: String) {
        http.execute(
            AuthQueries.RESEND_VERIFICATION,
            buildJsonObject { put("identifier", identifier) },
            JsonElement.serializer(),
        )
    }

    override suspend fun changePassword(token: String, newPassword: String, oldPassword: String) {
        http.execute(
            AuthQueries.CHANGE_PASSWORD,
            buildJsonObject { put("newPassword", newPassword); put("oldPassword", oldPassword) },
            JsonElement.serializer(),
            token,
        )
    }

    override suspend fun changeIdentifier(token: String, identifier: String, password: String) {
        http.execute(
            AuthQueries.CHANGE_IDENTIFIER,
            buildJsonObject { put("identifier", identifier); put("password", password) },
            JsonElement.serializer(),
            token,
        )
    }

    override suspend fun setPrimaryProfile(token: String, profileId: Uuid, principalId: Uuid?) {
        http.execute(
            AuthQueries.SET_PRIMARY_PROFILE,
            buildJsonObject {
                put("profileId", profileId.toString())
                put("principalId", principalId?.toString())
            },
            JsonElement.serializer(),
            token,
        )
    }

    override suspend fun getCurrentPrincipal(token: String): Principal {
        val data = http.execute(
            AuthQueries.GET_CURRENT_PRINCIPAL,
            emptyVariables(),
            CurrentPrincipalEnvelope.serializer(),
            token,
        )
        return data.security.principals.current.toDomain()
    }

    override suspend fun getCurrentProfiles(token: String): List<Profile> {
        val data = http.execute(
            AuthQueries.GET_CURRENT_PROFILES,
            emptyVariables(),
            CurrentProfilesEnvelope.serializer(),
            token,
        )
        return data.profiles.current.orEmpty().map { it.toDomain() }
    }

    override suspend fun getCurrentGroups(token: String): List<Group> {
        val data = http.execute(
            AuthQueries.GET_CURRENT_GROUPS,
            emptyVariables(),
            CurrentGroupsEnvelope.serializer(),
            token,
        )
        return data.security.principals.current.groups.map { it.toDomain() }
    }

    override suspend fun updateProfile(token: String, id: Uuid?, input: ProfileInput): Profile {
        val data = http.execute(
            AuthQueries.UPDATE_PROFILE,
            buildJsonObject {
                put("id", id?.toString())
                put("profile", input.toVariable())
            },
            EditProfileEnvelope.serializer(),
            token,
        )
        return (data.profiles.edit ?: throw GraphQLAuthError("Profile edit returned no profile")).toDomain()
    }

    private fun emptyVariables(): JsonObject = JsonObject(emptyMap())

    /** Encodes the domain [ProfileInput] as the GraphQL `ProfileInput` variable value. */
    private fun ProfileInput.toVariable(): JsonElement =
        AuthHttpClient.JSON.encodeToJsonElement(ProfileInput.serializer(), this)
}

/**
 * Maps a server error [code] (from a GraphQL error's `extensions.code`) to the typed [BoscaAuthError] the UI
 * can act on — the Kotlin port of the web client's `mapPasswordLoginError`. Falls back to message-substring
 * matching for older backends that don't carry a code, then to a generic [GraphQLAuthError]. Order matters in
 * the fallback: "principal not verified" also contains "not verified", so the specific phrases match first.
 */
internal fun toAuthError(code: String?, message: String, messages: List<String> = listOf(message)): BoscaAuthError {
    when (code) {
        "PRINCIPAL_NOT_VERIFIED" -> return PrincipalNotVerifiedError()
        "EMAIL_NOT_VERIFIED" -> return EmailNotVerifiedError()
        "INVALID_CREDENTIALS" -> return InvalidCredentialsError()
    }
    val lower = message.lowercase()
    return when {
        "principal not verified" in lower -> PrincipalNotVerifiedError()
        "email not verified" in lower -> EmailNotVerifiedError()
        // A bare "not verified" from a pre-split backend is the account-level gate that blocks login.
        "not verified" in lower -> PrincipalNotVerifiedError()
        "missing credentials" in lower || "invalid password" in lower -> InvalidCredentialsError()
        else -> GraphQLAuthError(message, messages)
    }
}
