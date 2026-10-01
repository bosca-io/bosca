package bosca.security.service

import bosca.di.ObjectProvider
import bosca.security.oauth2.DefaultOauth2User
import bosca.security.oauth2.FacebookUser
import bosca.security.oauth2.ThirdPartyUser
import bosca.server.http.await
import bosca.service.annotation.ServiceImplementation
import com.auth0.jwt.JWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Default [ThirdPartyTokenVerifier]: verifies a provider token over the network — Google's `tokeninfo`
 * endpoint, Facebook's Graph API (with an `appsecret_proof` HMAC), or an Apple identity JWT validated
 * against Apple's JWKS — and maps the asserted identity to a [ThirdPartyUser], including the
 * provider-verified-email signal.
 *
 * Extracted from `SecurityServiceImpl` so the sole network-bound seam of the OAuth flows lives behind a
 * mockable contract; the sign-up / login / connect logic that consumes it is then unit-testable.
 */
@ServiceImplementation
class ThirdPartyTokenVerifierImpl(
    private val securityConfiguration: ObjectProvider<SecurityConfiguration>,
    private val json: Json,
) : ThirdPartyTokenVerifier {

    private val httpClient = OkHttpClient()

    override suspend fun verify(type: ThirdPartyType, token: String): ThirdPartyUser {
        val configuration = securityConfiguration.get().oauth2.find { it.type.equals(type.name, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported third party type: $type")
        return when (type) {
            ThirdPartyType.GOOGLE -> verifyGoogleToken(token, configuration)
            ThirdPartyType.FACEBOOK -> verifyFacebookToken(token, configuration)
            ThirdPartyType.APPLE -> verifyAppleToken(token, configuration)
        }
    }

    private suspend fun verifyGoogleToken(token: String, configuration: OAuth2Provider): ThirdPartyUser {
        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/tokeninfo?id_token=$encodedToken")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) throw SecurityException("Invalid Google token")
        val body = response.body.string()
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val aud = jsonElement.requiredString("aud")
        if (aud != configuration.clientId && !configuration.supportedClientIds.contains(aud)) throw SecurityException("Invalid Google token audience")
        val sub = jsonElement.requiredString("sub")
        val email = jsonElement.optionalString("email")
        val name = jsonElement.optionalString("name")
        val picture = jsonElement.optionalString("picture")
        val givenName = jsonElement.optionalString("given_name")
        val familyName = jsonElement.optionalString("family_name")
        // Google's tokeninfo endpoint encodes email_verified as the string "true"; accept
        // either the string or a JSON boolean and default to false when absent.
        val emailVerified = jsonElement.boolean("email_verified")
        return DefaultOauth2User(sub, name, givenName, familyName, picture, email, emailVerifiedClaim = emailVerified)
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw SecurityException("Invalid Google token response")

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        return value.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = this[name] ?: return false
        val primitive = value.jsonPrimitive
        return primitive.booleanOrNull ?: primitive.content.equals("true", ignoreCase = true)
    }

    private suspend fun verifyFacebookToken(token: String, configuration: OAuth2Provider): ThirdPartyUser {
        fun generateAppSecretProof(accessToken: String, appSecret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(appSecret.toByteArray(), "HmacSHA256")
            mac.init(secretKey)
            val result = mac.doFinal(accessToken.toByteArray())
            return result.joinToString("") { "%02x".format(it) }
        }

        val appSecretProof = generateAppSecretProof(token, configuration.clientSecret)
        val request = Request.Builder()
            .url("https://graph.facebook.com/me?fields=id,name,email,picture&access_token=${URLEncoder.encode(token, StandardCharsets.UTF_8)}&appsecret_proof=${URLEncoder.encode(appSecretProof, StandardCharsets.UTF_8)}")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) throw SecurityException("Invalid Facebook token")
        val responseBody = response.body.string()
        return json.decodeFromString<FacebookUser>(responseBody)
    }

    private suspend fun verifyAppleToken(token: String, configuration: OAuth2Provider): ThirdPartyUser {
        return withContext(Dispatchers.IO) {
            val provider = com.auth0.jwk.UrlJwkProvider(URI.create("https://appleid.apple.com/auth/keys").toURL())
            val jwt = JWT.decode(token)
            val jwk = provider.get(jwt.keyId)
            val algorithm = com.auth0.jwt.algorithms.Algorithm.RSA256(jwk.publicKey as java.security.interfaces.RSAPublicKey, null)
            val verifier = JWT.require(algorithm)
                .withIssuer("https://appleid.apple.com")
                .withAudience(configuration.clientId)
                .build()
            verifier.verify(token)
            val sub = jwt.subject ?: throw SecurityException("Invalid Apple token")
            val email = jwt.getClaim("email").asString()
            val name = jwt.getClaim("name").asString()
            // Apple sends email_verified as either a boolean or the string "true".
            val emailVerifiedClaim = jwt.getClaim("email_verified")
            val emailVerified = emailVerifiedClaim.asBoolean()
                ?: emailVerifiedClaim.asString()?.equals("true", ignoreCase = true)
                ?: false
            DefaultOauth2User(sub, name, null, null, null, email, emailVerifiedClaim = emailVerified)
        }
    }
}
