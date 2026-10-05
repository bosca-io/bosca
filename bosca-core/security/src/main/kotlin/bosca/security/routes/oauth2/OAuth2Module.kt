package bosca.security.routes.oauth2

import bosca.cache.CacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.di.provide
import bosca.http.authenticationContext
import bosca.observability.ErrorCapture
import bosca.security.service.AccountLinkRequired
import bosca.security.service.OAuth2Provider
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.HttpStatusCode
import bosca.server.routing.RoutingContext
import io.opentelemetry.api.trace.Tracer
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Installs OAuth2 login and callback routes for all enabled OAuth2 providers
 * configured in the security settings. Coordinates authorization state management,
 * token exchange, user info retrieval, and session creation through dedicated
 * component classes for each concern.
 */
class OAuth2Module : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) = with(application) {
        val securityConfiguration = provide<SecurityConfiguration>()
        val securityService = provide<SecurityService>()
        val json = provide<Json>()
        val tracer = provide<Tracer>()
        val errorCapture = provide<ErrorCapture>()
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val cacheManager = provide<CacheManager>()
        val cache = cacheManager.maybeAddCache(OAuth2StateManager.STATE_CACHE_KEY, StringKeySerializer, 5.minutes)

        val stateManager = OAuth2StateManager(cache, json, securityConfiguration.allowedRedirects)
        val tokenExchanger = OAuth2TokenExchanger(httpClient, json)
        val userInfoFetcher = OAuth2UserInfoFetcher(httpClient, json)
        val callbackHandler = OAuth2CallbackHandler(
            securityService = securityService,
            securityConfiguration = securityConfiguration,
            tracer = tracer,
            log = log,
        )

        routing {
            securityConfiguration.oauth2.filter { it.enabled }.forEach { provider ->
                get("/oauth2/${provider.type}/login") {
                    val state = UUID.randomUUID().toString()
                    val codeChallenge = stateManager.createState(
                        call.request.queryParameters,
                        state,
                        provider.type,
                    )
                    val callbackUrl = resolveCallbackUrl(provider, call.request.queryParameters["admin"] == "true")
                    call.respondRedirect(buildAuthorizeUrl(provider, callbackUrl, state, codeChallenge))
                }
                get("/oauth2/${provider.type}/callback") {
                    val errorParam = call.request.queryParameters["error"]
                    if (errorParam != null) {
                        val errorDesc = call.request.queryParameters["error_description"] ?: "Authorization denied"
                        log.warn("OAuth2 callback error for {}: {} - {}", provider.type, errorParam, errorDesc)
                        call.respondRedirect("/?error=${URLEncoder.encode(errorDesc, Charsets.UTF_8)}")
                        return@get
                    }
                    val code = call.request.queryParameters["code"]
                    if (code == null) {
                        log.warn("OAuth2 callback missing authorization code for {}", provider.type)
                        call.respondRedirect("/?error=missing_code")
                        return@get
                    }
                    val stateParam = call.request.queryParameters["state"]
                    if (stateParam.isNullOrBlank()) {
                        log.error("State is null for OAuth2 callback (null state)")
                        errorCapture.capture(
                            IllegalStateException("State is null for OAuth2 callback (null state)"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.null"),
                        )
                        call.respondRedirect("/?error=oauth2.state.null")
                        return@get
                    }
                    val oauth2State = stateManager.retrieveAndRemoveState(stateParam)
                    if (oauth2State == null) {
                        log.error("State not found for OAuth2 callback")
                        errorCapture.capture(
                            IllegalStateException("State not found for OAuth2 callback"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.not.found"),
                        )
                        call.respondRedirect("/?error=oauth2.state.not.found")
                        return@get
                    }
                    if (oauth2State.provider != provider.type) {
                        log.error("OAuth2 state provider mismatch: expected={}, actual={}", provider.type, oauth2State.provider)
                        errorCapture.capture(
                            IllegalStateException("OAuth2 state provider mismatch: expected=${provider.type}, actual=${oauth2State.provider}"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.provider.mismatch"),
                        )
                        call.respondRedirect("/?error=oauth2.state.provider.mismatch")
                        return@get
                    }
                    val callbackUrl = resolveCallbackUrl(provider, oauth2State.admin)
                    val codeVerifier = provider.codeVerifier(oauth2State)
                    val (accessToken, _) = tokenExchanger.exchange(provider, code, callbackUrl, codeVerifier)
                    val user = userInfoFetcher.fetchUser(provider, accessToken)
                    try {
                        with(callbackHandler) { handleCallback(oauth2State, provider.type, user) }
                    } catch (e: AccountLinkRequired) {
                        // this provider email already belongs to a verified account, so we did not
                        // create a duplicate. Hand the single-use pending-link token to the app via the
                        // redirect so it can drive the proof/linking challenge.
                        log.info("OAuth2 callback for {}: verified account already exists for email, linking required", provider.type)
                        respondAccountLinkRequired(oauth2State, e)
                    }
                }
                authenticate(null, "session", "bearer") {
                    get("/oauth2/${provider.type}/connect") {
                        val authenticatedPrincipal = authenticationContext().principal()
                            ?: error("authenticated principal missing")
                        if (authenticatedPrincipal is ScopedAuthenticatedPrincipal) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                        val principalId = authenticatedPrincipal.id
                        val state = UUID.randomUUID().toString()
                        val codeChallenge = stateManager.createState(
                            call.request.queryParameters,
                            state,
                            provider.type,
                            connectPrincipalId = principalId,
                        )
                        val callbackUrl = resolveCallbackUrl(provider, false)
                        call.respondRedirect(buildAuthorizeUrl(provider, callbackUrl, state, codeChallenge))
                    }
                }
            }
            // Facebook legacy callback handler for Firebase-style redirect URLs
            securityConfiguration.oauth2.firstOrNull { it.enabled && it.type == "facebook" }?.let { provider ->
                get("/__/auth/handler") {
                    val errorParam = call.request.queryParameters["error"]
                    if (errorParam != null) {
                        val errorDesc = call.request.queryParameters["error_description"] ?: "Authorization denied"
                        log.warn("Facebook legacy callback error: {} - {}", errorParam, errorDesc)
                        call.respondRedirect("/?error=${URLEncoder.encode(errorDesc, Charsets.UTF_8)}")
                        return@get
                    }
                    val code = call.request.queryParameters["code"]
                    if (code == null) {
                        log.warn("Facebook legacy callback missing authorization code")
                        call.respondRedirect("/?error=missing_code")
                        return@get
                    }
                    val stateParam = call.request.queryParameters["state"]
                    if (stateParam.isNullOrBlank()) {
                        log.error("State is null for Facebook legacy OAuth2 callback")
                        errorCapture.capture(
                            IllegalStateException("State is null for Facebook legacy OAuth2 callback (null state)"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.null"),
                        )
                        call.respondRedirect("/?error=oauth2.state.null")
                        return@get
                    }
                    val oauth2State = stateManager.retrieveAndRemoveState(stateParam)
                    if (oauth2State == null) {
                        log.error("State not found for Facebook legacy OAuth2 callback")
                        errorCapture.capture(
                            IllegalStateException("State not found for Facebook legacy OAuth2 callback"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.not.found"),
                        )
                        call.respondRedirect("/?error=oauth2.state.not.found")
                        return@get
                    }
                    if (oauth2State.provider != provider.type) {
                        log.error("OAuth2 state provider mismatch in Facebook legacy callback: expected={}, actual={}", provider.type, oauth2State.provider)
                        errorCapture.capture(
                            IllegalStateException("OAuth2 state provider mismatch in Facebook legacy callback: expected=${provider.type}, actual=${oauth2State.provider}"),
                            call,
                            mapOf("oauth2.provider" to provider.type, "oauth2.error" to "state.provider.mismatch"),
                        )
                        call.respondRedirect("/?error=oauth2.state.provider.mismatch")
                        return@get
                    }
                    val codeVerifier = provider.codeVerifier(oauth2State)
                    val (accessToken, _) = tokenExchanger.exchange(provider, code, provider.callback, codeVerifier)
                    val user = userInfoFetcher.fetchUser(provider, accessToken)
                    try {
                        with(callbackHandler) { handleCallback(oauth2State, provider.type, user) }
                    } catch (e: AccountLinkRequired) {
                        // this provider email already belongs to a verified account, so we did not
                        // create a duplicate. Hand the single-use pending-link token to the app via the
                        // redirect so it can drive the proof/linking challenge.
                        log.info("OAuth2 callback for {}: verified account already exists for email, linking required", provider.type)
                        respondAccountLinkRequired(oauth2State, e)
                    }
                }
            }
        }
    }

    private fun resolveCallbackUrl(provider: OAuth2Provider, isAdmin: Boolean): String {
        return if (isAdmin) provider.adminCallback else provider.callback
    }

    private fun OAuth2Provider.codeVerifier(state: OAuth2State): String? =
        if (pkceEnabled) state.codeVerifier else null

    private fun RoutingContext.respondAccountLinkRequired(state: OAuth2State, error: AccountLinkRequired) {
        val separator = if ("?" in state.redirect) "&" else "?"
        val encodedToken = URLEncoder.encode(error.token, Charsets.UTF_8)
        // Carry only the proof methods that can succeed for the existing account.
        val encodedMethods = URLEncoder.encode(error.methods.joinToString(",") { it.name }, Charsets.UTF_8)
        call.respondRedirect("${state.redirect}${separator}link=$encodedToken&methods=$encodedMethods")
    }

    private fun buildAuthorizeUrl(provider: OAuth2Provider, callbackUrl: String, state: String, codeChallenge: String): String {
        return buildString {
            append(provider.authorizeUrl)
            append("?response_type=code&client_id=")
            append(URLEncoder.encode(provider.clientId, Charsets.UTF_8))
            append("&redirect_uri=")
            append(URLEncoder.encode(callbackUrl, Charsets.UTF_8))
            append("&scope=")
            append(URLEncoder.encode(provider.scopes.joinToString(" "), Charsets.UTF_8))
            append("&state=")
            append(URLEncoder.encode(state, Charsets.UTF_8))
            if (provider.pkceEnabled) {
                append("&code_challenge=")
                append(URLEncoder.encode(codeChallenge, Charsets.UTF_8))
                append("&code_challenge_method=S256")
            }
        }
    }
}
