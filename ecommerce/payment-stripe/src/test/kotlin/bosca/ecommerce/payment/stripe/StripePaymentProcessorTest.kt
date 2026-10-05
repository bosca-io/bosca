package bosca.ecommerce.payment.stripe

import bosca.ecommerce.model.CreditCard
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.serialization.UUID
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Stripe REST port (OkHttp): charges/tokens/customers/refunds, save-via-Customer, and error mapping. */
@OptIn(ExperimentalUuidApi::class)
class StripePaymentProcessorTest {

    private lateinit var server: MockWebServer
    private val forms = mutableMapOf<String, Map<String, String>>()
    private val headers = mutableMapOf<String, okhttp3.Headers>()

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    /** Route responses by Stripe endpoint path and record each request's form fields. */
    private fun processor(responses: Map<String, Pair<Int, String>>): StripePaymentProcessor {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                forms[path] = parseForm(request.body?.utf8() ?: "")
                headers[path] = request.headers
                val (code, body) = responses[path] ?: (404 to "{}")
                return MockResponse.Builder().code(code).body(body).build()
            }
        }
        return StripePaymentProcessor(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    private fun parseForm(s: String): Map<String, String> =
        s.split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf("="); URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    private fun provider(secret: String = "sk_test") = PaymentProvider(
        id = UUID.random(), companyId = UUID.random(), name = "Stripe", providerKey = "stripe",
        configuration = KeyValueProviderConfiguration(mapOf("secretKey" to secret)),
    )

    private fun card() = CreditCard(name = "Ada Lovelace", number = "4242424242424242", cvv = "123", expirationMonth = 5, expirationYear = 2030)

    private val paidCharge = """{"id":"ch_1","status":"succeeded","paid":true,"payment_intent":"pi_1"}"""

    @Test
    fun `submit charges a supplied token directly and records the charge id`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("19.99"), "EUR", token = "tok_abc", creditCard = null, save = false)

        assertTrue(result.complete)
        assertEquals("succeeded", result.status)
        // Records the charge id (ch_), NOT the payment_intent (pi_) — refund sends it as Stripe's `charge` param.
        assertEquals("ch_1", result.transactionId)
        assertEquals("stripe", result.providerId)
        assertNull(result.saved)
        assertEquals("tok_abc", forms["/v1/charges"]?.get("source"))
        assertEquals("1999", forms["/v1/charges"]?.get("amount"))
        // The supplied store currency is threaded to the charge (lowercased), not a provider-config default.
        assertEquals("eur", forms["/v1/charges"]?.get("currency"))
    }

    @Test
    fun `submit charges a zero-decimal currency in whole units, not hundredths`() = runTest {
        processor(mapOf("/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("1000.00"), "JPY", token = "tok", creditCard = null, save = false)
        // ¥1000 must post as 1000, NOT 100000 (a 100× overcharge from a hardcoded ×100).
        assertEquals("1000", forms["/v1/charges"]?.get("amount"))
        assertEquals("jpy", forms["/v1/charges"]?.get("currency"))
    }

    @Test
    fun `submit charges a three-decimal currency in thousandths`() = runTest {
        processor(mapOf("/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("1.500"), "BHD", token = "tok", creditCard = null, save = false)
        assertEquals("1500", forms["/v1/charges"]?.get("amount"))
    }

    @Test
    fun `submit tokenizes a raw card before charging`() = runTest {
        val result = processor(mapOf("/v1/tokens" to (200 to """{"id":"tok_made"}"""), "/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("5.00"), "usd", token = null, creditCard = card(), save = false)
        assertTrue(result.complete)
        assertEquals("4242424242424242", forms["/v1/tokens"]?.get("card[number]"))
        assertEquals("tok_made", forms["/v1/charges"]?.get("source"))
    }

    @Test
    fun `submit with save creates a customer and returns its id as the reusable token`() = runTest {
        val result = processor(mapOf("/v1/customers" to (200 to """{"id":"cus_99"}"""), "/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("10.00"), "usd", token = "tok_x", creditCard = null, save = true)
        assertTrue(result.complete)
        assertEquals(SavedPaymentMethod("stripe", "cus_99"), result.saved)
        assertEquals("tok_x", forms["/v1/customers"]?.get("source"))
        assertEquals("cus_99", forms["/v1/charges"]?.get("customer"))
    }

    @Test
    fun `submit with save does not return a token when the charge fails`() = runTest {
        val result = processor(mapOf("/v1/customers" to (200 to """{"id":"cus_1"}"""), "/v1/charges" to (200 to """{"id":"ch_2","status":"failed","paid":false,"failure_message":"card_declined"}""")))
            .submit(provider(), Money.of("10.00"), "usd", token = "tok_x", creditCard = null, save = true)
        assertTrue(!result.complete)
        assertNull(result.saved)
        assertEquals("card_declined", result.error)
    }

    @Test
    fun `submit without a token or card fails fast`() = runTest {
        val result = processor(emptyMap()).submit(provider(), Money.of("1.00"), "usd", token = null, creditCard = null, save = false)
        assertTrue(!result.complete)
        assertEquals("error", result.status)
    }

    @Test
    fun `submit fails when tokenization is rejected`() = runTest {
        val result = processor(mapOf("/v1/tokens" to (400 to """{"error":{"message":"invalid card"}}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = null, creditCard = card(), save = false)
        assertTrue(!result.complete)
    }

    @Test
    fun `submit with save fails when customer creation is rejected`() = runTest {
        val result = processor(mapOf("/v1/customers" to (400 to """{"error":{"message":"bad"}}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok_x", creditCard = null, save = true)
        assertTrue(!result.complete)
        assertEquals("Stripe customer creation failed", result.error)
    }

    @Test
    fun `a charge http error maps the stripe error message`() = runTest {
        val result = processor(mapOf("/v1/charges" to (402 to """{"error":{"message":"Your card was declined."}}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok_x", creditCard = null, save = false)
        assertTrue(!result.complete)
        assertEquals("Your card was declined.", result.error)
    }

    @Test
    fun `an unparseable error body yields a generic message`() = runTest {
        val result = processor(mapOf("/v1/charges" to (500 to "not json")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok_x", creditCard = null, save = false)
        assertEquals("Stripe request failed", result.error)
        assertTrue(result.transportFailure) // a non-2xx is a transport failure, not a decline
    }

    @Test
    fun `a connection failure surfaces as a transport failure`() = runTest {
        // Dead endpoint -> the OkHttp call throws IOException, which post() maps to a non-ok (transport) result
        // so the charge is flagged transportFailure and renewal dunning skips it rather than dunning.
        val offline = StripePaymentProcessor(OkHttpClient(), baseUrl = "http://localhost:1")
        val result = offline.charge(provider(), SavedPaymentMethod("stripe", "cus_x"), Money.of("5.00"), "usd")

        assertTrue(!result.complete)
        assertTrue(result.transportFailure)
    }

    @Test
    fun `a 2xx charge with an unparseable body is a clean failure, not an exception`() = runTest {
        // A 200 with a non-JSON body (proxy/HTML/partial) must map to a recorded failure, not an uncaught
        // ClassCastException that would unwind the caller's transaction after a possible charge.
        val result = processor(mapOf("/v1/charges" to (200 to "<html>maintenance</html>")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok_x", creditCard = null, save = false)
        assertTrue(!result.complete)
    }

    @Test
    fun `a charge response with only a payment_intent falls back to it`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to """{"payment_intent":"pi_only","paid":true}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok_x", creditCard = null, save = false)
        assertEquals("pi_only", result.transactionId)
    }

    @Test
    fun `submit sends a stable idempotency key as a header so a retry cannot double-charge`() = runTest {
        processor(mapOf("/v1/charges" to (200 to paidCharge)))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false, idempotencyKey = "renewal:abc:123")
        assertEquals("renewal:abc:123", headers["/v1/charges"]?.get("Idempotency-Key"))
    }

    @Test
    fun `refund posts the charge and amount`() = runTest {
        // Stripe derives the refund currency from the original charge, so it is not sent here.
        val result = processor(mapOf("/v1/refunds" to (200 to """{"id":"re_1","status":"succeeded"}""")))
            .refund(provider(), originalTransactionId = "ch_1", amount = Money.of("4.00"), currency = "usd")
        assertTrue(result.complete)
        assertEquals("ch_1", forms["/v1/refunds"]?.get("charge"))
        assertEquals("400", forms["/v1/refunds"]?.get("amount"))
        assertNull(forms["/v1/refunds"]?.get("currency"))
    }

    @Test
    fun `refund reports a gateway error`() = runTest {
        val result = processor(mapOf("/v1/refunds" to (400 to """{"error":{"message":"no such charge"}}""")))
            .refund(provider(), originalTransactionId = "ch_x", amount = Money.of("1.00"), currency = "usd")
        assertTrue(!result.complete)
        assertEquals("no such charge", result.error)
    }

    @Test
    fun `charge re-bills a saved customer`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to paidCharge)))
            .charge(provider(), SavedPaymentMethod("stripe", "cus_77"), Money.of("9.00"), "usd")
        assertTrue(result.complete)
        assertEquals("cus_77", forms["/v1/charges"]?.get("customer"))
        assertEquals("900", forms["/v1/charges"]?.get("amount"))
    }

    @Test
    fun `missing secret key is rejected`() = runTest {
        val noConfig = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "x", providerKey = "stripe", configuration = EmptyProviderConfiguration)
        assertFailsWith<IllegalStateException> {
            processor(emptyMap()).charge(noConfig, SavedPaymentMethod("stripe", "cus"), Money.of("1.00"), "usd")
        }
    }

    @Test
    fun `a charge response missing optional fields falls back to the charge id`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to """{"id":"ch_only","paid":true}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertTrue(result.complete)
        assertEquals("ch_only", result.transactionId)
        assertNull(result.status)
    }

    @Test
    fun `a non-boolean paid field is treated as not paid`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to """{"id":"ch","paid":"yes"}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertTrue(!result.complete)
    }

    @Test
    fun `an explicit paid false is honored`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to """{"id":"c","paid":false,"status":"failed","failure_message":"no"}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertTrue(!result.complete)
        assertEquals("no", result.error)
    }

    @Test
    fun `a json null field reads as null`() = runTest {
        val result = processor(mapOf("/v1/charges" to (200 to """{"id":null,"payment_intent":null,"paid":true}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertTrue(result.complete)
        assertNull(result.transactionId)
    }

    @Test
    fun `a pending refund is complete but not yet confirmed`() = runTest {
        val result = processor(mapOf("/v1/refunds" to (200 to """{"id":"re_2","status":"pending"}""")))
            .refund(provider(), originalTransactionId = "ch_1", amount = Money.of("1.00"), currency = "usd")
        assertTrue(result.complete)
        assertTrue(!result.confirmed)
    }

    @Test
    fun `refund tolerates a null original transaction id`() = runTest {
        val result = processor(mapOf("/v1/refunds" to (200 to """{"id":"re_3","status":"succeeded"}""")))
            .refund(provider(), originalTransactionId = null, amount = Money.of("1.00"), currency = "usd")
        assertTrue(result.complete)
        assertNull(forms["/v1/refunds"]?.get("charge"))
    }

    @Test
    fun `an error body without an error object yields the generic message`() = runTest {
        val result = processor(mapOf("/v1/charges" to (400 to "{}")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertEquals("Stripe request failed", result.error)
    }

    @Test
    fun `an error field that is not an object yields the generic message`() = runTest {
        val result = processor(mapOf("/v1/charges" to (400 to """{"error":"boom"}""")))
            .submit(provider(), Money.of("1.00"), "usd", token = "tok", creditCard = null, save = false)
        assertEquals("Stripe request failed", result.error)
    }

    @Test
    fun `the DI factory builds a stripe processor`() {
        assertEquals("stripe", StripeConfiguration().stripePaymentProcessor().key)
    }
}
