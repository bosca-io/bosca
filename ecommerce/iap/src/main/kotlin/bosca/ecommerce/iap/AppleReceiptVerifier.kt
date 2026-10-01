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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Apple App Store receipt verifier. Ported from the legacy `bosca.iap.apple.AppleReceiptVerifier`, kept
 * on OkHttp but with **explicit hand-written JSON reads** (no `@Serializable` response DTOs) for
 * GraalVM-native-safety.
 *
 * POSTs the receipt to Apple's `verifyReceipt` endpoint (production unless [sandbox]) and reads back the
 * `status` (0 = valid) and the receipt's `download_id`. Implements Apple's documented endpoint fallback:
 * a production call that returns 21007 (a sandbox receipt) is retried against sandbox, and a sandbox call
 * that returns 21008 (a production receipt) is retried against production.
 */
class AppleReceiptVerifier(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled store must not pin the thread
        .build(),
    private val productionUrl: String = "https://buy.itunes.apple.com/verifyReceipt",
    private val sandboxUrl: String = "https://sandbox.itunes.apple.com/verifyReceipt",
) : IapReceiptVerifier {

    override val key: String = "iap-apple"
    override val platform: IapPlatform = IapPlatform.IOS

    override suspend fun verify(
        token: String,
        productId: String?,
        packageName: String?,
        credentials: Map<String, String>,
        sandbox: Boolean,
    ): IapReceipt {
        val body = buildJsonObject {
            put("receipt-data", token)
            credentials["password"]?.let { put("password", it) }
            credentials["excludeOldTransactions"]?.let { put("exclude-old-transactions", it.toBoolean()) }
        }
        val first = if (sandbox) sandboxUrl else productionUrl
        val firstResponse = post(first, body) ?: return unavailable()
        // Apple tells us when a receipt belongs to the other environment — retry there once. Do NOT fall back
        // to the wrong-environment response if the retry can't be completed: that would read a 21007/21008
        // (an environment mismatch, not a verdict) as INVALID and wrongly deny a real purchase.
        val result = when (firstResponse.intOrNull("status")) {
            21007 -> post(sandboxUrl, body) ?: return unavailable()
            21008 -> post(productionUrl, body) ?: return unavailable()
            else -> firstResponse
        }
        return when (val status = result.intOrNull("status")) {
            0 -> validReceipt(result, productId) ?: unavailable()
            // Apple-side transient codes (receipt server unavailable / internal error) and a missing status are
            // not a verdict — the caller must neither grant nor treat as fraud.
            null, 21005, 21009 -> {
                log.error("Apple receipt verification unavailable (status=$status)")
                unavailable()
            }
            // Everything else (malformed / unauthenticated / secret-mismatch / expired / account-gone) is a
            // genuine "this is not a valid purchase".
            else -> IapReceipt(platform = platform, status = IapVerificationStatus.INVALID, environment = result.str("environment"))
        }
    }

    private fun validReceipt(result: JsonObject, productId: String?): IapReceipt? {
        val receipt = result["receipt"] as? JsonObject
        val transactionId = receipt?.str("download_id") ?: receipt?.str("original_transaction_id")
        if (transactionId == null) {
            log.error("Apple receipt verification returned status 0 with no transaction id")
            return null
        }
        return IapReceipt(
            platform = platform,
            status = IapVerificationStatus.VALID,
            transactionId = transactionId,
            productId = productId ?: receipt?.str("bundle_id"),
            environment = result.str("environment"),
        )
    }

    private fun unavailable() = IapReceipt(platform = platform, status = IapVerificationStatus.VERIFICATION_UNAVAILABLE)

    /** Returns the parsed body on a 2xx, or null (logged) on a non-2xx, unparseable body, or transport failure. */
    private suspend fun post(url: String, body: JsonObject): JsonObject? {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        return try {
            val raw = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                .use { if (it.isSuccessful) it.body.string() else null }
            if (raw == null) {
                log.error("Apple verifyReceipt returned a non-2xx response")
                return null
            }
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: run { log.error("Apple verifyReceipt returned an unparseable body"); null }
        } catch (e: IOException) {
            log.error("Apple verifyReceipt transport failure: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        private val JSON = "application/json".toMediaType()
        private val log = LoggerFactory.getLogger(AppleReceiptVerifier::class.java)
    }
}
