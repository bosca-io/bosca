package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.client
import bosca.bml.render.currentRenderContext
import bosca.profiles.web.graphql.LoginProviders
import java.net.URLEncoder

internal fun profilesOAuthUrl(
    provider: String,
    action: String,
    returnPath: String,
    publicUrl: String = profilesWebPublicUrl(),
): String {
    // OAuth callbacks arrive on the Bosca API origin, so a profiles-web return is cross-domain and
    // carries a one-time exchangeToken query parameter. Return through the public login page rather
    // than a guarded destination: otherwise BML's SSR auth gate redirects before browser JavaScript
    // can consume the token and buries it inside the login page's `redirect` parameter.
    val safeReturnPath = returnPath.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: "/"
    val loginPath = "/login?redirect=${URLEncoder.encode(safeReturnPath, Charsets.UTF_8)}"
    val redirect = URLEncoder.encode(profilesWebRedirect(loginPath, publicUrl), Charsets.UTF_8)
    return "/oauth2/$provider/$action?redirect=$redirect&originator=profiles-web"
}

suspend fun loginProviders(): List<LoginProviderRow> {
    val requested = currentRenderContext().query["redirect"]
        ?.takeIf { it.startsWith("/") && !it.startsWith("//") }
        ?: "/"
    return client().execute(LoginProviders, Unit).security.thirdPartyProviders.map { provider ->
        val key = provider.toString().lowercase()
        LoginProviderRow(
            name = key.replaceFirstChar(Char::uppercase),
            loginUrl = profilesOAuthUrl(key, "login", requested),
        )
    }
}
