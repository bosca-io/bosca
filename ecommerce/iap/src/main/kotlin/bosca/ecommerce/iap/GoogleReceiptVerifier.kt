package bosca.ecommerce.iap

import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapReceipt
import bosca.ecommerce.model.IapVerificationStatus
import bosca.ecommerce.service.IapReceiptVerifier
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Google Play in-app-purchase verifier. Ported from the legacy `bosca.iap.android.AndroidReceiptVerifier`
 * but **without the `androidpublisher` Java SDK** (reflection-heavy / GraalVM-hostile): it mints a Google
 * OAuth2 access token by signing a JWT with JDK crypto (`SHA256withRSA`) and calls the Play Developer
 * REST API directly over OkHttp with explicit JSON reads.
 *
 * Flow: build + RS256-sign a service-account assertion → exchange it at the OAuth token endpoint for an
 * access token → `GET .../purchases/products/{productId}/tokens/{token}` → read `orderId` and
 * `purchaseState` (0 = purchased). Credentials: `email` (service-account email) + `privateKey` (its PEM
 * private key); `packageName` from the call or the credentials bag.
 */
class GoogleReceiptVerifier(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled store/token endpoint must not pin the thread
        .build(),
    private val baseUrl: String = "https://androidpublisher.googleapis.com",
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : IapReceiptVerifier {

    override val key: String = "iap-google"
    override val platform: IapPlatform = IapPlatform.ANDROID

    override suspend fun verify(
        token: String,
        productId: String?,
        packageName: String?,
        credentials: Map<String, String>,
        sandbox: Boolean,
    ): IapReceipt {
        val email = credentials["email"].orEmpty()
        val privateKey = credentials["privateKey"].orEmpty()
        val pkg = packageName ?: credentials["packageName"]
        if (email.isEmpty() || privateKey.isEmpty() || pkg == null || productId == null) {
            log.error("Google IAP verification misconfigured (email/privateKey/packageName/productId missing)")
            return unavailable()
        }

        val accessToken = accessToken(email, privateKey) ?: return unavailable() // already logged inside
        val url = "$baseUrl/androidpublisher/v3/applications/$pkg/purchases/products/$productId/tokens/$token"
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $accessToken").get().build()
        val (code, purchase) = request(request)
        return when {
            code == 200 && purchase != null -> {
                val orderId = purchase.str("orderId")
                if (orderId == null) {
                    log.error("Google IAP: 200 response with no orderId")
                    unavailable()
                } else {
                    IapReceipt(
                        platform = platform,
                        // 0 = Purchased (1 = Canceled, 2 = Pending) — only Purchased is a valid entitlement.
                        status = if (purchase.intOrNull("purchaseState") == 0) IapVerificationStatus.VALID else IapVerificationStatus.INVALID,
                        transactionId = orderId,
                        productId = productId,
                    )
                }
            }
            // The store has no record of this purchase token — a genuine NOT_FOUND, not an outage.
            code == 404 -> IapReceipt(platform = platform, status = IapVerificationStatus.NOT_FOUND, productId = productId)
            else -> {
                log.error("Google IAP purchase lookup failed (httpCode=$code)")
                unavailable()
            }
        }
    }

    private fun unavailable() = IapReceipt(platform = platform, status = IapVerificationStatus.VERIFICATION_UNAVAILABLE)

    /** Mint a Play Developer access token from the service account via a signed JWT bearer grant. */
    private suspend fun accessToken(email: String, privateKeyPem: String): String? {
        val assertion = runCatching { assertion(email, privateKeyPem) }.getOrNull()
        if (assertion == null) {
            // A bad/garbled service-account private key fails here — log it rather than swallowing silently.
            log.error("Google IAP: could not build/sign the service-account JWT (check the privateKey)")
            return null
        }
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()
        val (code, body) = request(Request.Builder().url(tokenUrl).post(form).build())
        val accessToken = body?.str("access_token")
        if (accessToken == null) log.error("Google IAP: OAuth token exchange failed (httpCode=$code)")
        return accessToken
    }

    private fun assertion(email: String, privateKeyPem: String): String {
        val now = nowEpochSeconds()
        val header = encode("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = encode(
            buildJsonObject {
                put("iss", email)
                put("scope", "https://www.googleapis.com/auth/androidpublisher")
                put("aud", tokenUrl)
                put("iat", now)
                put("exp", now + 3600)
            }.toString().toByteArray(),
        )
        val signingInput = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey(privateKeyPem))
            update(signingInput.toByteArray())
        }.sign()
        return "$signingInput.${encode(signature)}"
    }

    private fun privateKey(pem: String): java.security.PrivateKey {
        val der = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(der)))
    }

    /** Returns `(httpCode or null on a transport failure, parsed 2xx body or null)`. */
    private suspend fun request(request: Request): Pair<Int?, JsonObject?> {
        return try {
            val (code, raw) = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                .use { it.code to (if (it.isSuccessful) it.body.string() else null) }
            code to raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        } catch (e: IOException) {
            log.error("Google IAP HTTP transport failure: ${e.message ?: e.javaClass.simpleName}")
            null to null
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        private val log = LoggerFactory.getLogger(GoogleReceiptVerifier::class.java)
    }
}
