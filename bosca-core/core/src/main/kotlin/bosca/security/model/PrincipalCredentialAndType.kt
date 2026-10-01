package bosca.security.model

data class PrincipalCredentialAndType(
    val identifier: String,
    val type: CredentialType,
    /** OAuth provider key (for example `google`), null for non-OAuth credentials. */
    val provider: String? = null,
    /** The original originator: where this credential first came from (creation-time). Null when none. */
    val originator: String? = null,
    /** The last originator: where the most recent login using this credential came from. Null when none. */
    val lastOriginator: String? = null
)
