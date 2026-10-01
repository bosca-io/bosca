package bosca.ecommerce.iap

import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapVerificationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Apple App Store verifyReceipt port (OkHttp): status parsing, the 21007/21008 environment fallback, and field extraction. */
class AppleReceiptVerifierTest {

    private lateinit var server: MockWebServer
    private val bodies = mutableMapOf<String, String>()

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    private fun verifier(responses: Map<String, Pair<Int, String>>): AppleReceiptVerifier {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                bodies[path] = request.body?.utf8() ?: ""
                val (code, body) = responses[path] ?: (404 to "{}")
                return MockResponse.Builder().code(code).body(body).build()
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        return AppleReceiptVerifier(OkHttpClient(), productionUrl = "$base/prod", sandboxUrl = "$base/sandbox")
    }

    private fun bodyAt(path: String): JsonObject = Json.parseToJsonElement(bodies.getValue(path)) as JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    @Test
    fun `verify reads a valid production receipt and extracts the download id`() = runTest {
        val ok = """{"status":0,"environment":"Production","receipt":{"download_id":"123456","bundle_id":"io.bosca.app"}}"""
        val receipt = verifier(mapOf("/prod" to (200 to ok)))
            .verify("RECEIPT", productId = null, packageName = null, credentials = mapOf("password" to "secret", "excludeOldTransactions" to "true"))

        assertEquals(IapVerificationStatus.VALID, receipt.status)
        assertEquals(IapPlatform.IOS, receipt.platform)
        assertEquals("123456", receipt.transactionId)
        assertEquals("io.bosca.app", receipt.productId) // bundle_id fallback when productId not supplied
        assertTrue(receipt.valid)
        assertEquals("Production", receipt.environment)
        // The receipt data + credentials were posted in Apple's field names.
        val sent = bodyAt("/prod")
        assertEquals("RECEIPT", sent.str("receipt-data"))
        assertEquals("secret", sent.str("password"))
        assertEquals(true, sent.bool("exclude-old-transactions"))
    }

    @Test
    fun `verify retries against sandbox when production returns 21007`() = runTest {
        val sandboxOk = """{"status":0,"environment":"Sandbox","receipt":{"download_id":"999"}}"""
        val receipt = verifier(mapOf("/prod" to (200 to """{"status":21007}"""), "/sandbox" to (200 to sandboxOk)))
            .verify("R", productId = "io.bosca.pro")

        assertEquals(IapVerificationStatus.VALID, receipt.status)
        assertEquals("999", receipt.transactionId)
        assertEquals("io.bosca.pro", receipt.productId) // explicit productId wins over bundle_id
        assertEquals("Sandbox", receipt.environment)
        assertTrue(bodies.containsKey("/prod") && bodies.containsKey("/sandbox"))
    }

    @Test
    fun `verify retries against production when sandbox returns 21008`() = runTest {
        val prodOk = """{"status":0,"environment":"Production","receipt":{"download_id":"42"}}"""
        val receipt = verifier(mapOf("/sandbox" to (200 to """{"status":21008}"""), "/prod" to (200 to prodOk)))
            .verify("R", sandbox = true)
        assertEquals(IapVerificationStatus.VALID, receipt.status)
        assertEquals("42", receipt.transactionId)
    }

    @Test
    fun `verify reports INVALID for a non-zero verdict status`() = runTest {
        // 21003: the receipt could not be authenticated — a genuine "not a valid purchase".
        val receipt = verifier(mapOf("/prod" to (200 to """{"status":21003,"receipt":{"original_transaction_id":"OT-1"}}"""))).verify("R")
        assertEquals(IapVerificationStatus.INVALID, receipt.status)
        assertFalse(receipt.valid)
    }

    @Test
    fun `verify reports UNAVAILABLE on an Apple-side transient status, not INVALID`() = runTest {
        // 21005 (receipt server unavailable) / 21009 (internal error) are not a verdict — must not read as fraud.
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to """{"status":21005}"""))).verify("R").status)
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to """{"status":21009}"""))).verify("R").status)
    }

    @Test
    fun `verify reports UNAVAILABLE when status is 0 but there is no transaction id`() = runTest {
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to """{"status":0,"receipt":{}}"""))).verify("R").status)
    }

    @Test
    fun `verify reports UNAVAILABLE on a missing or non-integer status (shape drift)`() = runTest {
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to "{}"))).verify("R").status)
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to """{"status":"oops","receipt":{"download_id":"7"}}"""))).verify("R").status)
    }

    @Test
    fun `verify reports UNAVAILABLE when Apple is unreachable or returns a non-object body`() = runTest {
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (500 to "{}"))).verify("R").status)
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, verifier(mapOf("/prod" to (200 to "[]"))).verify("R").status) // valid JSON, not an object
    }

    @Test
    fun `verify does NOT fall back to the wrong-environment response when the retry fails`() = runTest {
        // prod says 21007 (sandbox receipt) but the sandbox retry errors: must be UNAVAILABLE, NOT a wrong verdict.
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/prod" to (200 to """{"status":21007}"""), "/sandbox" to (500 to "{}"))).verify("R").status,
        )
        // symmetric 21008 case: sandbox-first, production retry errors.
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/sandbox" to (200 to """{"status":21008}"""), "/prod" to (500 to "{}"))).verify("R", sandbox = true).status,
        )
    }

    @Test
    fun `verify reports UNAVAILABLE on a connection failure`() = runTest {
        val offline = AppleReceiptVerifier(OkHttpClient(), productionUrl = "http://localhost:1/prod", sandboxUrl = "http://localhost:1/sandbox")
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, offline.verify("R").status)
    }
}
