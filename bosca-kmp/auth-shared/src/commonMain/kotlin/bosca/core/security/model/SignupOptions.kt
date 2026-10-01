package bosca.core.security.model

/**
 * Options for creating a new account with email/password and an initial
 * profile. Port of `SignupOptions` from types.ts.
 */
data class SignupOptions(
    val identifier: String,
    val password: String,
    val profile: ProfileInput,
    val languageTag: String? = null,
)
