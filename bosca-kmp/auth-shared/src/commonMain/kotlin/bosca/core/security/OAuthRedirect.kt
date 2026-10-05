package bosca.core.security

import bosca.core.security.model.OAuthRedirectOptions

/**
 * Navigates to the backend's OAuth login endpoint, starting a redirect-based
 * sign-in. Port of `oauth.ts` `startOAuthRedirect`.
 *
 * Implemented on the web target (the browser navigates away); a no-op on
 * Android/iOS/Desktop, which use the native token flow
 * (`signInWithThirdParty`) instead — mirroring the TS guard on `window`.
 */
expect fun startOAuthRedirect(apiUrl: String, options: OAuthRedirectOptions)

/**
 * Reads (and strips) the `exchangeToken` query parameter the backend appends
 * when redirecting back after OAuth. Port of `oauth.ts`
 * `getExchangeTokenFromUrl`. Returns `null` on non-web targets or when no
 * exchange token is present.
 */
expect fun getExchangeTokenFromUrl(): String?

/**
 * Whether this platform signs in with third-party providers via the
 * server-redirect flow ([startOAuthRedirect]) rather than a native token flow.
 * True only on web, where there is no native provider SDK — the browser
 * navigates to the backend's `/oauth2/<provider>/login` endpoint and the
 * sign-in completes on the next page load via [getExchangeTokenFromUrl].
 */
expect val oauthSignInUsesRedirect: Boolean
