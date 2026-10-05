package bosca.security.routes.oauth2

import bosca.security.oauth2.DefaultOauth2User
import bosca.security.oauth2.FacebookUser
import bosca.security.oauth2.ThirdPartyUser
import bosca.security.service.OAuth2Provider
import bosca.server.http.await
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fetches user profile information from an OAuth2 provider's user info endpoint,
 * handling provider-specific API differences such as Facebook's app secret proof
 * requirement and different response deserialization formats.
 */
class OAuth2UserInfoFetcher(
    private val httpClient: OkHttpClient,
    private val json: Json
) {

    /**
     * Fetches the authenticated user's profile from the [provider] using the given
     * [accessToken], returning a provider-agnostic [ThirdPartyUser] representation.
     */
    suspend fun fetchUser(provider: OAuth2Provider, accessToken: String): ThirdPartyUser {
        val response = when (provider.type) {
            "facebook" -> fetchFacebookUser(provider, accessToken)
            else -> fetchGenericUser(provider.userInfoUrl, accessToken)
        }
        if (!response.isSuccessful) {
            error("Failed to fetch user info from ${provider.type}")
        }
        val body = response.body.string()
        return when (provider.type) {
            "facebook" -> json.decodeFromString<FacebookUser>(body)
            else -> json.decodeFromString<DefaultOauth2User>(body)
        }
    }

    private suspend fun fetchGenericUser(url: String, accessToken: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return httpClient.newCall(request).await()
    }

    private suspend fun fetchFacebookUser(provider: OAuth2Provider, accessToken: String): okhttp3.Response {
        val appSecretProof = generateAppSecretProof(accessToken, provider.clientSecret)
        val url = buildString {
            append("https://graph.facebook.com/me?fields=id,name,email,picture&access_token=")
            append(URLEncoder.encode(accessToken, Charsets.UTF_8))
            append("&appsecret_proof=")
            append(URLEncoder.encode(appSecretProof, Charsets.UTF_8))
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return httpClient.newCall(request).await()
    }

    private fun generateAppSecretProof(accessToken: String, appSecret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(appSecret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val result = mac.doFinal(accessToken.toByteArray())
        return result.joinToString("") { "%02x".format(it) }
    }
}
