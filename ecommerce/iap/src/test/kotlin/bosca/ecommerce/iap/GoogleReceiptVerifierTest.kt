package bosca.ecommerce.iap

import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapVerificationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Google Play port: service-account JWT minting (RS256, JDK crypto), token exchange, and purchase lookup. */
class GoogleReceiptVerifierTest {

    private lateinit var server: MockWebServer
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder().encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----"
    private val bodies = mutableMapOf<String, String>()
    private val authHeaders = mutableMapOf<String, String?>()
    private val fixedNow = 1_700_000_000L

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    private fun verifier(responses: Map<String, Pair<Int, String>>): GoogleReceiptVerifier {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                bodies[path] = request.body?.utf8() ?: ""
                authHeaders[path] = request.headers["Authorization"]
                val match = responses[path] ?: responses.entries.firstOrNull { path.startsWith(it.key) }?.value ?: (404 to "{}")
                return MockResponse.Builder().code(match.first).body(match.second).build()
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        return GoogleReceiptVerifier(OkHttpClient(), baseUrl = base, tokenUrl = "$base/token", nowEpochSeconds = { fixedNow })
    }

    private fun creds(email: String = "svc@bosca.iam.gserviceaccount.com", key: String = pem) =
        mapOf("email" to email, "privateKey" to key)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun decodeSegment(s: String): JsonObject = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(s))) as JsonObject

    @Test
    fun `verify mints a signed token, looks up the purchase, and reports it valid`() = runTest {
        val purchase = """{"orderId":"GPA.1234","purchaseState":0,"consumptionState":0}"""
        val receipt = verifier(mapOf("/token" to (200 to """{"access_token":"ya29.abc"}"""), "/androidpublisher" to (200 to purchase)))
            .verify("PURCHASE_TOKEN", productId = "premium", packageName = "io.bosca.app", credentials = creds())

        assertEquals(IapVerificationStatus.VALID, receipt.status)
        assertEquals(IapPlatform.ANDROID, receipt.platform)
        assertEquals("GPA.1234", receipt.transactionId)
        assertEquals("premium", receipt.productId)
        assertTrue(receipt.valid)

        // The purchase lookup carried the minted bearer token, on the documented v3 path.
        val purchasePath = bodies.keys.first { it.startsWith("/androidpublisher") }
        assertEquals("Bearer ya29.abc", authHeaders[purchasePath])
        assertEquals("/androidpublisher/v3/applications/io.bosca.app/purchases/products/premium/tokens/PURCHASE_TOKEN", purchasePath)

        // The JWT assertion is a real RS256 signature over header.claims, with Google's required claims.
        val assertion = URLDecoder.decode(bodies.getValue("/token"), "UTF-8").substringAfter("assertion=").substringBefore("&")
        val (h, c, sig) = assertion.split(".")
        assertEquals("RS256", decodeSegment(h).str("alg"))
        val claims = decodeSegment(c)
        assertEquals("svc@bosca.iam.gserviceaccount.com", claims.str("iss"))
        assertEquals("https://www.googleapis.com/auth/androidpublisher", claims.str("scope"))
        assertTrue(claims.str("aud")!!.endsWith("/token"))
        assertEquals(fixedNow.toString(), claims.str("iat"))
        assertEquals((fixedNow + 3600).toString(), claims.str("exp"))
        val verifies = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("$h.$c".toByteArray())
        }.verify(Base64.getUrlDecoder().decode(sig))
        assertTrue(verifies)
    }

    @Test
    fun `verify reports INVALID for a non-purchased state`() = runTest {
        val canceled = """{"orderId":"GPA.9","purchaseState":1}"""
        val receipt = verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (200 to canceled)))
            .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds())
        assertEquals(IapVerificationStatus.INVALID, receipt.status)
        assertFalse(receipt.valid)
    }

    @Test
    fun `verify reports INVALID for a missing or non-integer purchase state`() = runTest {
        val noState = """{"orderId":"GPA.1"}"""
        val r1 = verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (200 to noState)))
            .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds())
        assertEquals(IapVerificationStatus.INVALID, r1.status)

        val weirdState = """{"orderId":"GPA.2","purchaseState":"weird"}"""
        val r2 = verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (200 to weirdState)))
            .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds())
        assertEquals(IapVerificationStatus.INVALID, r2.status)
    }

    @Test
    fun `verify reports UNAVAILABLE when the token endpoint returns a non-object body`() = runTest {
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/token" to (200 to "[]")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
    }

    @Test
    fun `verify takes the package name from credentials when not passed explicitly`() = runTest {
        val purchase = """{"orderId":"GPA.7","purchaseState":0}"""
        val receipt = verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (200 to purchase)))
            .verify("TK", productId = "p", packageName = null, credentials = creds() + ("packageName" to "io.bosca.fromcreds"))
        assertEquals(IapVerificationStatus.VALID, receipt.status)
        assertTrue(bodies.keys.any { it.contains("/applications/io.bosca.fromcreds/") })
    }

    @Test
    fun `verify reports UNAVAILABLE when required credentials or ids are missing`() = runTest {
        val v = verifier(emptyMap())
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, v.verify("TK", productId = "p", packageName = "io.bosca.app", credentials = mapOf("privateKey" to pem)).status) // no email
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, v.verify("TK", productId = "p", packageName = "io.bosca.app", credentials = mapOf("email" to "e")).status) // no key
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, v.verify("TK", productId = "p", packageName = null, credentials = creds()).status) // no package
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, v.verify("TK", productId = null, packageName = "io.bosca.app", credentials = creds()).status) // no product
    }

    @Test
    fun `verify reports UNAVAILABLE when the private key cannot be parsed`() = runTest {
        val receipt = verifier(mapOf("/token" to (200 to """{"access_token":"t"}""")))
            .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = mapOf("email" to "e", "privateKey" to "not-a-key"))
        assertEquals(IapVerificationStatus.VERIFICATION_UNAVAILABLE, receipt.status)
    }

    @Test
    fun `verify reports UNAVAILABLE when the token exchange yields no access token`() = runTest {
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/token" to (401 to """{"error":"invalid_grant"}""")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/token" to (200 to """{"scope":"x"}""")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
    }

    @Test
    fun `verify maps the purchase lookup outcome to NOT_FOUND, UNAVAILABLE, or omitted-id`() = runTest {
        // 404: the store has no record of this token -> a genuine NOT_FOUND, distinct from an outage.
        assertEquals(
            IapVerificationStatus.NOT_FOUND,
            verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (404 to "{}")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
        // Any other non-2xx on the lookup is an outage -> UNAVAILABLE.
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (500 to "{}")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
        // A 200 with no orderId is shape drift -> UNAVAILABLE.
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            verifier(mapOf("/token" to (200 to """{"access_token":"t"}"""), "/androidpublisher" to (200 to """{"purchaseState":0}""")))
                .verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
    }

    @Test
    fun `verify reports UNAVAILABLE on a connection failure`() = runTest {
        val offline = GoogleReceiptVerifier(OkHttpClient(), baseUrl = "http://localhost:1", tokenUrl = "http://localhost:1/token", nowEpochSeconds = { fixedNow })
        assertEquals(
            IapVerificationStatus.VERIFICATION_UNAVAILABLE,
            offline.verify("TK", productId = "p", packageName = "io.bosca.app", credentials = creds()).status,
        )
    }

    @Test
    fun `the configuration registers both store verifiers under their keys`() {
        assertEquals("iap-apple", IapConfiguration().appleReceiptVerifier().key)
        assertEquals("iap-google", IapConfiguration().googleReceiptVerifier().key)
    }
}
