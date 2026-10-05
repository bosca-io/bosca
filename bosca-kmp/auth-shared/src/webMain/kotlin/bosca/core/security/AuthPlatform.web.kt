package bosca.core.security

import bosca.core.security.model.OAuthRedirectOptions
import kotlinx.browser.window
import org.w3c.dom.url.URL

/**
 * Web (js + wasmJs) actuals for the auth platform seams.
 *
 * The cross-tab refresh lock currently runs [block] directly — correctness is
 * guaranteed by the token manager's in-process coalescing and its
 * adopt-fresh-tokens-from-storage path (the same fallback the TS uses when the
 * Web Locks API is unavailable). A real `navigator.locks` implementation can be
 * dropped into the js leaf source set later as a multi-tab optimization.
 */
actual suspend fun <T> withAuthRefreshLock(name: String, block: suspend () -> T): T = block()

actual fun startOAuthRedirect(apiUrl: String, options: OAuthRedirectOptions) {
    val provider = options.provider.rawValue.lowercase()
    val redirect = options.redirectUrl ?: window.location.href
    val url = URL("$apiUrl/oauth2/$provider/login")
    url.searchParams.append("redirect", redirect)
    options.organization?.let { url.searchParams.append("organization", it) }
    options.community?.let { url.searchParams.append("community", it) }
    window.location.href = url.href
}

actual val oauthSignInUsesRedirect: Boolean = true

@OptIn(ExperimentalWasmJsInterop::class)
actual fun getExchangeTokenFromUrl(): String? {
    val url = URL(window.location.href)
    val token = url.searchParams.get("exchangeToken") ?: return null
    // Strip the param so a page refresh can't replay the single-use token.
    url.searchParams.delete("exchangeToken")
    window.history.replaceState(null, "", url.href)
    return token
}
