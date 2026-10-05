package bosca.core.security

/**
 * Raw GraphQL operation documents for the auth library. `auth-shared`
 * hand-rolls its transport ([AuthHttpClient]) instead of running Apollo
 * codegen, so the operations live here as plain strings. `${'$'}` escapes the
 * GraphQL variable sigil inside Kotlin string templates.
 *
 * The `LoginResponseFields` fragment is appended to every token-issuing
 * operation so each returns a complete [bosca.core.security.model.AuthResponse].
 */
internal object AuthQueries {

    private val LOGIN_RESPONSE_FIELDS = """
        fragment LoginResponseFields on LoginResponse {
          principal { id verified primaryProfileId }
          profile {
            id name type visibility slug isPrimary
            attributes { typeId attributes source priority confidence visibility }
          }
          token { token expiresAt issuedAt }
          refreshToken
        }
    """.trimIndent()

    val LOGIN_PASSWORD = """
        mutation LoginPassword(${'$'}identifier: String!, ${'$'}password: String!) {
          security { login { password(identifier: ${'$'}identifier, password: ${'$'}password) { ...LoginResponseFields } } }
        }
        $LOGIN_RESPONSE_FIELDS
    """.trimIndent()

    val REFRESH_TOKEN = """
        mutation RefreshToken(${'$'}refreshToken: String!) {
          security { login { refreshToken(refreshToken: ${'$'}refreshToken) { ...LoginResponseFields } } }
        }
        $LOGIN_RESPONSE_FIELDS
    """.trimIndent()

    val EXCHANGE_TOKEN = """
        mutation ExchangeToken(${'$'}token: String!) {
          security { login { exchangeToken(token: ${'$'}token) { ...LoginResponseFields } } }
        }
        $LOGIN_RESPONSE_FIELDS
    """.trimIndent()

    val SIGN_OUT = """
        mutation SignOut { security { login { signOut } } }
    """.trimIndent()

    val FORGOT_PASSWORD = """
        mutation ForgotPassword(${'$'}identifier: String!) {
          security { login { forgotPassword(identifier: ${'$'}identifier) } }
        }
    """.trimIndent()

    val RESET_PASSWORD = """
        mutation ResetPassword(${'$'}token: String!, ${'$'}password: String!) {
          security { login { resetPassword(token: ${'$'}token, password: ${'$'}password) } }
        }
    """.trimIndent()

    val SIGNUP_PASSWORD = """
        mutation SignupPassword(${'$'}identifier: String!, ${'$'}password: String!, ${'$'}profile: ProfileInput!, ${'$'}languageTag: String) {
          security { signup { password(identifier: ${'$'}identifier, password: ${'$'}password, profile: ${'$'}profile, languageTag: ${'$'}languageTag) { id verified } } }
        }
    """.trimIndent()

    val SIGN_IN_WITH_THIRD_PARTY = """
        mutation SignInWithThirdParty(${'$'}type: ThirdPartyType!, ${'$'}token: String!, ${'$'}languageTag: String) {
          security { signup { thirdparty(type: ${'$'}type, token: ${'$'}token, languageTag: ${'$'}languageTag) { ...LoginResponseFields } } }
        }
        $LOGIN_RESPONSE_FIELDS
    """.trimIndent()

    val VERIFY_EMAIL = """
        mutation VerifyEmail(${'$'}verificationToken: String!) {
          security { signup { passwordVerify(verificationToken: ${'$'}verificationToken) } }
        }
    """.trimIndent()

    val RESEND_VERIFICATION = """
        mutation ResendVerification(${'$'}identifier: String!) {
          security { signup { resendPasswordVerification(identifier: ${'$'}identifier) } }
        }
    """.trimIndent()

    val CHANGE_PASSWORD = """
        mutation ChangePassword(${'$'}newPassword: String!, ${'$'}oldPassword: String!) {
          security { principal { password(newPassword: ${'$'}newPassword, oldPassword: ${'$'}oldPassword) } }
        }
    """.trimIndent()

    val CHANGE_IDENTIFIER = """
        mutation ChangeIdentifier(${'$'}identifier: String!, ${'$'}password: String!) {
          security { principal { identifier(identifier: ${'$'}identifier, password: ${'$'}password) } }
        }
    """.trimIndent()

    val SET_PRIMARY_PROFILE = """
        mutation SetPrimaryProfile(${'$'}profileId: UUID!, ${'$'}principalId: UUID) {
          security { principal { setPrimaryProfile(profileId: ${'$'}profileId, principalId: ${'$'}principalId) } }
        }
    """.trimIndent()

    val GET_CURRENT_PRINCIPAL = """
        query GetCurrentPrincipal {
          security { principals { current {
            id verified primaryProfileId
            credentials { identifier type }
            profiles { id name }
          } } }
        }
    """.trimIndent()

    val GET_CURRENT_PROFILES = """
        query GetCurrentProfiles {
          profiles { current {
            id name type visibility slug isPrimary
            attributes { typeId attributes source priority confidence visibility }
          } }
        }
    """.trimIndent()

    val GET_CURRENT_GROUPS = """
        query GetCurrentGroups {
          security { principals { current { groups { id name description type } } } }
        }
    """.trimIndent()

    val UPDATE_PROFILE = """
        mutation UpdateProfile(${'$'}id: UUID, ${'$'}profile: ProfileInput!) {
          profiles { edit(id: ${'$'}id, profile: ${'$'}profile) {
            id name type visibility slug isPrimary
            attributes { typeId attributes source priority confidence visibility }
          } }
        }
    """.trimIndent()
}
