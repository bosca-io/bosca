package bosca.core.security

import bosca.core.security.model.OAuthRedirectOptions

// Single-process target: no cross-context refresh race, so just run the block.
actual suspend fun <T> withAuthRefreshLock(name: String, block: suspend () -> T): T = block()

// Redirect-based OAuth is web-only; Android uses the native token flow
// (signInWithThirdParty via ThirdPartyAuthenticationProvider).
actual fun startOAuthRedirect(apiUrl: String, options: OAuthRedirectOptions) = Unit

actual fun getExchangeTokenFromUrl(): String? = null

actual val oauthSignInUsesRedirect: Boolean = false
