package bosca.core.security

import bosca.core.security.type.ThirdPartyType
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.Group
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import kotlin.uuid.Uuid

/**
 * Typed wrapper over the auth GraphQL operations. Port of `graphql.ts`, but
 * built on the dedicated [AuthApolloClient] (no auto-refresh interceptor)
 * rather than raw fetch, attaching bearer tokens per call where required.
 *
 * Every method throws a [BoscaAuthError] on failure: [NetworkError] for
 * transport problems (so callers can distinguish a recoverable blip from a
 * terminal auth failure) and [GraphQLAuthError] for server-reported errors.
 */
interface AuthGraphql {

    suspend fun loginWithPassword(identifier: String, password: String): AuthResponse

    suspend fun refreshToken(refreshToken: String): AuthResponse

    suspend fun exchangeToken(token: String): AuthResponse

    suspend fun signOut(token: String?)

    suspend fun forgotPassword(identifier: String)

    suspend fun resetPassword(token: String, password: String)

    suspend fun signupWithPassword(options: SignupOptions): Principal

    suspend fun signupThirdParty(type: ThirdPartyType, token: String, languageTag: String?): AuthResponse

    suspend fun verifyEmail(verificationToken: String)

    suspend fun resendVerification(identifier: String)

    suspend fun changePassword(token: String, newPassword: String, oldPassword: String)

    suspend fun changeIdentifier(token: String, identifier: String, password: String)

    suspend fun setPrimaryProfile(token: String, profileId: Uuid, principalId: Uuid?)

    suspend fun getCurrentPrincipal(token: String): Principal

    suspend fun getCurrentProfiles(token: String): List<Profile>

    suspend fun getCurrentGroups(token: String): List<Group>

    suspend fun updateProfile(token: String, id: Uuid?, input: ProfileInput): Profile
}
