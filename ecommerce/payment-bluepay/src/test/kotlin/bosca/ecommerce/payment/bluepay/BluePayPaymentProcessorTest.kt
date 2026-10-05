package bosca.ecommerce.payment.bluepay

import bosca.ecommerce.model.CreditCard
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.serialization.UUID
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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

/** BluePay gateway port (OkHttp): form-post + MD5 seal, response parsing, save/refund/rebill, config guards. */
@OptIn(ExperimentalUuidApi::class)
class BluePayPaymentProcessorTest {

    private lateinit var server: MockWebServer
    private lateinit var processor: BluePayPaymentProcessor

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        processor = BluePayPaymentProcessor(OkHttpClient(), endpoint = server.url("/bp").toString())
    }

    @AfterTest
    fun teardown() = server.close()

    private fun enqueue(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    /** The form fields BluePay received on the last request. */
    private fun lastForm(): Map<String, String> =
        server.takeRequest().body!!.utf8().split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf("="); URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    private fun provider() = PaymentProvider(
        id = UUID.random(), companyId = UUID.random(), name = "BluePay", providerKey = "bluepay",
        configuration = KeyValueProviderConfiguration(mapOf("accountId" to "ACC1", "secretKey" to "SEC1", "mode" to "LIVE")),
    )

    private fun card(name: String = "Ada Lovelace", month: Int = 5, year: Int = 2027) =
        CreditCard(name = name, number = "4111111111111111", cvv = "123", expirationMonth = month, expirationYear = year)

    @Test
    fun `submit posts a SALE and approves on STATUS 1`() = runTest {
        enqueue("STATUS=1&TRANS_ID=tx-123&AVS=Y&TRANS_TYPE=SALE&MESSAGE=APPROVED")
        val result = processor.submit(provider(), Money.of("19.99"), "USD", token = null, creditCard = card(), save = false)

        assertTrue(result.complete)
        assertEquals("APPROVED", result.status)
        assertEquals("tx-123", result.transactionId)
        assertEquals("bluepay", result.providerId)
        assertNull(result.saved)
        val form = lastForm()
        assertEquals("ACC1", form["ACCOUNT_ID"])
        assertEquals("19.99", form["AMOUNT"])
        assertEquals("SALE", form["TRANS_TYPE"])
        assertEquals("LIVE", form["MODE"])
        assertEquals("4111111111111111", form["PAYMENT_ACCOUNT"])
        assertEquals("0527", form["CARD_EXPIRE"])
        assertEquals("Ada", form["NAME1"])
        assertEquals("Lovelace", form["NAME2"])
    }

    @Test
    fun `submit with save returns a reusable token only when approved`() = runTest {
        enqueue("STATUS=1&TRANS_ID=tx-9")
        assertEquals(SavedPaymentMethod("bluepay", "tx-9"), processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = true).saved)

        enqueue("STATUS=2&TRANS_ID=tx-9")
        assertNull(processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = true).saved)
    }

    @Test
    fun `a non-2xx response is a distinct ERROR, not parsed as a decline`() = runTest {
        // A WAF/HTML/timeout page lacks STATUS; without the isSuccessful check it would read as an indistinct
        // decline. The HTTP status must surface as a distinct ERROR so a refund fails loudly.
        server.enqueue(MockResponse.Builder().code(500).body("<html>gateway error</html>").build())
        val result = processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false)

        assertTrue(!result.complete)
        assertEquals("ERROR", result.status)
        assertTrue(result.error?.contains("500") == true)
        assertTrue(result.transportFailure) // a non-2xx is a transport failure, not a decline
    }

    @Test
    fun `a connection failure surfaces as a transport failure, not a decline`() = runTest {
        // Point at a dead endpoint: the OkHttp call throws IOException (ConnectException), which must map to a
        // transport-failure result so renewal dunning skips it rather than counting it as a decline.
        val offline = BluePayPaymentProcessor(OkHttpClient(), endpoint = "http://localhost:1/bp")
        val result = offline.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false)

        assertTrue(!result.complete)
        assertEquals("ERROR", result.status)
        assertTrue(result.transportFailure)
    }

    @Test
    fun `a STATUS 2 decline is not a transport failure`() = runTest {
        enqueue("STATUS=2&MESSAGE=DECLINED")
        val result = processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false)

        assertTrue(!result.complete)
        assertEquals("DECLINE", result.status)
        assertTrue(!result.transportFailure) // a clean 2xx decline DOES count toward dunning
    }

    @Test
    fun `submit rejects a non-USD currency without calling the gateway`() = runTest {
        // No response is enqueued — the guard must reject before any HTTP request (BluePay is USD-only).
        val result = processor.submit(provider(), Money.of("10.00"), "EUR", null, card(), save = false)
        assertTrue(!result.complete)
        assertEquals("ERROR", result.status)
        assertTrue(result.error?.contains("USD") == true)
    }

    @Test
    fun `submit sends a stable idempotency key as ORDER_ID`() = runTest {
        enqueue("STATUS=1&TRANS_ID=tx-1")
        processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false, idempotencyKey = "renewal:abc:1")
        assertEquals("renewal:abc:1", lastForm()["ORDER_ID"])
    }

    @Test
    fun `charge re-bill sends a stable idempotency key as ORDER_ID`() = runTest {
        enqueue("STATUS=1&TRANS_ID=tx-2")
        processor.charge(provider(), SavedPaymentMethod("bluepay", "master-1"), Money.of("5.00"), "USD", idempotencyKey = "renewal:abc:2")
        assertEquals("renewal:abc:2", lastForm()["ORDER_ID"])
    }

    @Test
    fun `submit maps STATUS 2 to DECLINE and other to ERROR`() = runTest {
        enqueue("STATUS=2&MESSAGE=insufficient")
        val declined = processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false)
        assertEquals("DECLINE", declined.status)
        assertTrue(!declined.complete)
        assertEquals("insufficient", declined.error)

        enqueue("STATUS=9")
        assertEquals("ERROR", processor.submit(provider(), Money.of("5.00"), "USD", null, card(), save = false).status)
    }

    @Test
    fun `submit requires a raw card`() = runTest {
        assertFailsWith<IllegalStateException> {
            processor.submit(provider(), Money.of("5.00"), "USD", token = "tok", creditCard = null, save = false)
        }
    }

    @Test
    fun `a single-word cardholder name omits NAME2`() = runTest {
        enqueue("STATUS=1&TRANS_ID=t")
        processor.submit(provider(), Money.of("5.00"), "USD", null, card(name = "Prince"), save = false)
        val form = lastForm()
        assertEquals("Prince", form["NAME1"])
        assertNull(form["NAME2"])
    }

    @Test
    fun `expiration date formats two-digit month and four-digit and short years`() = runTest {
        enqueue("STATUS=1")
        processor.submit(provider(), Money.of("1.00"), "USD", null, card(month = 11, year = 2030), save = false)
        assertEquals("1130", lastForm()["CARD_EXPIRE"])
        enqueue("STATUS=1")
        processor.submit(provider(), Money.of("1.00"), "USD", null, card(month = 3, year = 6), save = false)
        assertEquals("0306", lastForm()["CARD_EXPIRE"])
    }

    @Test
    fun `refund posts a REFUND against the original transaction`() = runTest {
        enqueue("STATUS=1&TRANS_ID=rf-1&TRANS_TYPE=REFUND")
        val result = processor.refund(provider(), originalTransactionId = "tx-orig", amount = Money.of("4.00"), currency = "USD")
        assertTrue(result.complete)
        val form = lastForm()
        assertEquals("REFUND", form["TRANS_TYPE"])
        assertEquals("tx-orig", form["MASTER_ID"])
        assertEquals("4.00", form["AMOUNT"])
    }

    @Test
    fun `charge re-bills a saved transaction via MASTER_ID`() = runTest {
        enqueue("STATUS=1&TRANS_ID=rb-1")
        val result = processor.charge(provider(), SavedPaymentMethod("bluepay", "tx-saved"), Money.of("9.00"), "USD")
        assertTrue(result.complete)
        val form = lastForm()
        assertEquals("SALE", form["TRANS_TYPE"])
        assertEquals("tx-saved", form["MASTER_ID"])
    }

    @Test
    fun `missing credentials are rejected`() = runTest {
        val noConfig = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "x", providerKey = "bluepay", configuration = EmptyProviderConfiguration)
        assertFailsWith<IllegalStateException> { processor.submit(noConfig, Money.of("1.00"), "USD", null, card(), save = false) }
        val blank = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "x", providerKey = "bluepay", configuration = KeyValueProviderConfiguration(mapOf("accountId" to "A")))
        assertFailsWith<IllegalStateException> { processor.submit(blank, Money.of("1.00"), "USD", null, card(), save = false) }
    }

    @Test
    fun `mode defaults to TEST when not LIVE`() = runTest {
        val testMode = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "x", providerKey = "bluepay", configuration = KeyValueProviderConfiguration(mapOf("accountId" to "A", "secretKey" to "S")))
        enqueue("STATUS=1")
        processor.submit(testMode, Money.of("1.00"), "USD", null, card(), save = false)
        assertEquals("TEST", lastForm()["MODE"])
    }

    @Test
    fun `a non-LIVE explicit mode still resolves to TEST`() = runTest {
        val p = PaymentProvider(id = UUID.random(), companyId = UUID.random(), name = "x", providerKey = "bluepay", configuration = KeyValueProviderConfiguration(mapOf("accountId" to "A", "secretKey" to "S", "mode" to "sandbox")))
        enqueue("STATUS=1")
        processor.submit(p, Money.of("1.00"), "USD", null, card(), save = false)
        assertEquals("TEST", lastForm()["MODE"])
    }

    @Test
    fun `the response parser ignores malformed pairs`() = runTest {
        enqueue("STATUS=1&JUNK&=novalue&TRANS_ID=ok")
        val result = processor.submit(provider(), Money.of("1.00"), "USD", null, card(), save = false)
        assertTrue(result.complete)
        assertEquals("ok", result.transactionId)
    }

    @Test
    fun `save yields no token when an approval has no transaction id`() = runTest {
        enqueue("STATUS=1")
        val result = processor.submit(provider(), Money.of("1.00"), "USD", null, card(), save = true)
        assertTrue(result.complete)
        assertNull(result.saved)
    }

    @Test
    fun `refund tolerates a null original transaction id`() = runTest {
        enqueue("STATUS=1&TRANS_ID=rf")
        processor.refund(provider(), originalTransactionId = null, amount = Money.of("1.00"), currency = "USD")
        assertEquals("", lastForm()["MASTER_ID"])
    }

    @Test
    fun `the DI factory builds a bluepay processor`() {
        assertEquals("bluepay", BluePayConfiguration().bluePayPaymentProcessor().key)
    }
}
