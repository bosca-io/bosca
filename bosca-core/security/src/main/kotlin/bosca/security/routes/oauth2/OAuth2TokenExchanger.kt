package bosca.security.routes.oauth2

import bosca.security.service.OAuth2Provider
import bosca.server.http.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * Exchanges an OAuth2 authorization code for an access token by making
 * an HTTP POST request to the provider's token endpoint using the standard
 * authorization code grant flow.
 */
class OAuth2TokenExchanger(
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(OAuth2TokenExchanger::class.java)

    /**
     * Performs the authorization code exchange with the given [provider], returning
     * the access token and an optional state value from the token response.
     *
     * When [codeVerifier] is provided, it is included in the token request as
     * the PKCE `code_verifier` parameter per RFC 7636.
     */
    suspend fun exchange(provider: OAuth2Provider, code: String, callbackUrl: String, codeVerifier: String? = null): Pair<String, String?> {
        val requestBody = buildString {
            append("grant_type=authorization_code&code=")
            append(URLEncoder.encode(code, Charsets.UTF_8))
            append("&redirect_uri=")
            append(URLEncoder.encode(callbackUrl, Charsets.UTF_8))
            append("&client_id=")
            append(URLEncoder.encode(provider.clientId, Charsets.UTF_8))
            // Always include client_secret for confidential (server-side) clients
            append("&client_secret=")
            append(URLEncoder.encode(provider.clientSecret, Charsets.UTF_8))
            if (codeVerifier != null) {
                // PKCE: include code_verifier alongside client_secret for defense-in-depth
                append("&code_verifier=")
                append(URLEncoder.encode(codeVerifier, Charsets.UTF_8))
            }
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url(provider.accessTokenUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            log.warn("Token exchange failed with HTTP {} for provider {}: {}", response.code, provider.type, body)
            error("Token exchange failed with HTTP ${response.code}")
        }
        val tokenResponse = json.decodeFromString<Map<String, JsonElement>>(body)

        val accessToken = tokenResponse["access_token"]?.let {
            if (it is JsonPrimitive) it.content else null
        } ?: error("No access_token in response")

        val returnedState = tokenResponse["state"]?.let {
            if (it is JsonPrimitive) it.content else null
        }

        return accessToken to returnedState
    }
}
