package bosca.core.security.model

import bosca.core.security.type.ThirdPartyType

/**
 * Options for initiating a redirect-based OAuth sign-in with a third-party
 * provider. Port of `OAuthRedirectOptions` from types.ts; [provider] reuses
 * the generated [ThirdPartyType] enum.
 */
data class OAuthRedirectOptions(
    val provider: ThirdPartyType,
    /** URL to return to after OAuth completes (defaults to the current page on web). */
    val redirectUrl: String? = null,
    /** Organization signup token granting access to a specific organization. */
    val organization: String? = null,
    /** Community signup token granting access to a specific community. */
    val community: String? = null,
)
