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
 * Configurable fake [AuthGraphql] for the TokenManager and BoscaAuth test
 * suites. [refreshToken] supports queued behaviors (mirroring vitest's
 * `mockResolvedValueOnce`/`mockRejectedValueOnce`) plus a fallback; the other
 * operations are driven by overridable handler lambdas that throw until set.
 */
class FakeAuthGraphql : AuthGraphql {

    // --- refreshToken: queue + fallback + call recording ---------------------

    val refreshCalls: MutableList<String> = mutableListOf()
    private val refreshQueue: ArrayDeque<suspend (String) -> AuthResponse> = ArrayDeque()
    var refreshDefault: (suspend (String) -> AuthResponse)? = null

    fun enqueueRefresh(behavior: suspend (String) -> AuthResponse) = refreshQueue.add(behavior)
    fun enqueueRefreshSuccess(response: AuthResponse) = enqueueRefresh { response }
    fun enqueueRefreshError(error: Throwable) = enqueueRefresh { throw error }

    override suspend fun refreshToken(refreshToken: String): AuthResponse {
        refreshCalls.add(refreshToken)
        val behavior = if (refreshQueue.isNotEmpty()) refreshQueue.removeFirst() else refreshDefault
        ?: throw IllegalStateException("FakeAuthGraphql.refreshToken called with no queued/default behavior")
        return behavior(refreshToken)
    }

    // --- other operations: overridable handlers ------------------------------

    var loginWithPasswordHandler: (suspend (String, String) -> AuthResponse)? = null
    var exchangeTokenHandler: (suspend (String) -> AuthResponse)? = null
    var signOutHandler: (suspend (String?) -> Unit)? = null
    var forgotPasswordHandler: (suspend (String) -> Unit)? = null
    var resetPasswordHandler: (suspend (String, String) -> Unit)? = null
    var signupWithPasswordHandler: (suspend (SignupOptions) -> Principal)? = null
    var signupThirdPartyHandler: (suspend (ThirdPartyType, String, String?) -> AuthResponse)? = null
    var verifyEmailHandler: (suspend (String) -> Unit)? = null
    var resendVerificationHandler: (suspend (String) -> Unit)? = null
    var changePasswordHandler: (suspend (String, String, String) -> Unit)? = null
    var changeIdentifierHandler: (suspend (String, String, String) -> Unit)? = null
    var setPrimaryProfileHandler: (suspend (String, Uuid, Uuid?) -> Unit)? = null
    var getCurrentPrincipalHandler: (suspend (String) -> Principal)? = null
    var getCurrentProfilesHandler: (suspend (String) -> List<Profile>)? = null
    var getCurrentGroupsHandler: (suspend (String) -> List<Group>)? = null
    var updateProfileHandler: (suspend (String, Uuid?, ProfileInput) -> Profile)? = null

    override suspend fun loginWithPassword(identifier: String, password: String): AuthResponse =
        loginWithPasswordHandler?.invoke(identifier, password) ?: notSet("loginWithPassword")

    override suspend fun exchangeToken(token: String): AuthResponse =
        exchangeTokenHandler?.invoke(token) ?: notSet("exchangeToken")

    override suspend fun signOut(token: String?) =
        signOutHandler?.invoke(token) ?: notSet("signOut")

    override suspend fun forgotPassword(identifier: String) =
        forgotPasswordHandler?.invoke(identifier) ?: notSet("forgotPassword")

    override suspend fun resetPassword(token: String, password: String) =
        resetPasswordHandler?.invoke(token, password) ?: notSet("resetPassword")

    override suspend fun signupWithPassword(options: SignupOptions): Principal =
        signupWithPasswordHandler?.invoke(options) ?: notSet("signupWithPassword")

    override suspend fun signupThirdParty(type: ThirdPartyType, token: String, languageTag: String?): AuthResponse =
        signupThirdPartyHandler?.invoke(type, token, languageTag) ?: notSet("signupThirdParty")

    override suspend fun verifyEmail(verificationToken: String) =
        verifyEmailHandler?.invoke(verificationToken) ?: notSet("verifyEmail")

    override suspend fun resendVerification(identifier: String) =
        resendVerificationHandler?.invoke(identifier) ?: notSet("resendVerification")

    override suspend fun changePassword(token: String, newPassword: String, oldPassword: String) =
        changePasswordHandler?.invoke(token, newPassword, oldPassword) ?: notSet("changePassword")

    override suspend fun changeIdentifier(token: String, identifier: String, password: String) =
        changeIdentifierHandler?.invoke(token, identifier, password) ?: notSet("changeIdentifier")

    override suspend fun setPrimaryProfile(token: String, profileId: Uuid, principalId: Uuid?) =
        setPrimaryProfileHandler?.invoke(token, profileId, principalId) ?: notSet("setPrimaryProfile")

    override suspend fun getCurrentPrincipal(token: String): Principal =
        getCurrentPrincipalHandler?.invoke(token) ?: notSet("getCurrentPrincipal")

    override suspend fun getCurrentProfiles(token: String): List<Profile> =
        getCurrentProfilesHandler?.invoke(token) ?: notSet("getCurrentProfiles")

    override suspend fun getCurrentGroups(token: String): List<Group> =
        getCurrentGroupsHandler?.invoke(token) ?: notSet("getCurrentGroups")

    override suspend fun updateProfile(token: String, id: Uuid?, input: ProfileInput): Profile =
        updateProfileHandler?.invoke(token, id, input) ?: notSet("updateProfile")

    private fun notSet(name: String): Nothing =
        throw IllegalStateException("FakeAuthGraphql.$name called but no handler was set")
}
