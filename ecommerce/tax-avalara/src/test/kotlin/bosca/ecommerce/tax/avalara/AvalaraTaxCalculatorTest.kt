package bosca.ecommerce.tax.avalara

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Avalara AvaTax port (OkHttp): SalesOrder estimate, Basic auth, and jurisdiction-summary -> Tax mapping. */
@OptIn(ExperimentalUuidApi::class)
class AvalaraTaxCalculatorTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    private fun config(
        accountId: String = "12345",
        licenseKey: String = "lic-key",
        companyCode: String = "DEFAULT",
        sandbox: Boolean = false,
    ) = AvalaraConfig(
        accountId = accountId, licenseKey = licenseKey, companyCode = companyCode, sandbox = sandbox,
        origin = AvalaraAddress(line1 = "1 Warehouse Way", city = "Reno", region = "NV", postalCode = "89501", country = "US"),
    )

    private fun calc(cfg: AvalaraConfig = config()): AvalaraTaxCalculator =
        AvalaraTaxCalculator(cfg, OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'), today = { "2026-06-19" })

    private fun address() = CartAddress(
        cartId = UUID.random(), type = AddressType.SHIPPING, firstName = "A", lastName = "B",
        address1 = "200 Market St", city = "San Francisco", state = "CA", country = "US", zip = "94105", phone = "5",
    )

    private fun enqueue(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `calculate posts a SalesOrder estimate and maps the jurisdiction summary to Tax components`() = runTest {
        enqueue(
            200,
            """{"totalTax":1.50,"summary":[
                {"jurisType":"State","tax":1.00},
                {"jurisType":"County","tax":0.25},
                {"jurisType":"City","tax":0.15},
                {"jurisType":"Country","tax":0.05},
                {"jurisType":"Special","tax":0.05}
            ]}""",
        )

        val tax = calc().calculate(Money.of("100.00"), address())

        assertEquals(Money.of("0.05"), tax.country)
        assertEquals(Money.of("1.00"), tax.state)
        assertEquals(Money.of("0.15"), tax.city)
        assertEquals(Money.of("0.30"), tax.district) // County 0.25 + Special 0.05
        assertEquals(Money.of("1.50"), tax.taxes)    // derived sum

        val sent = server.takeRequest()
        assertEquals("/api/v2/transactions/create", sent.url.encodedPath)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("12345:lic-key".toByteArray()), sent.headers["Authorization"])
        val body = Json.parseToJsonElement(sent.body!!.utf8()) as JsonObject
        assertEquals("SalesOrder", body.str("type"))
        assertEquals("DEFAULT", body.str("companyCode"))
        assertEquals("2026-06-19", body.str("date"))
        val addresses = body["addresses"] as JsonObject
        assertEquals("1 Warehouse Way", (addresses["shipFrom"] as JsonObject).str("line1"))
        assertEquals("San Francisco", (addresses["shipTo"] as JsonObject).str("city"))
        assertEquals("CA", (addresses["shipTo"] as JsonObject).str("region"))
    }

    @Test
    fun `calculate routes unknown jurisdiction types into the district and tolerates missing fields`() = runTest {
        // Row 1: an unrecognized type. Row 2: no jurisType and no tax at all (both default safely).
        enqueue(200, """{"summary":[{"jurisType":"Mystery","tax":0.42},{}]}""")
        val tax = calc().calculate(Money.of("10.00"), address())
        assertEquals(Money.of("0.42"), tax.district)
        assertEquals(Money.of("0.42"), tax.taxes)
    }

    @Test
    fun `calculate fails loud on a success body with no usable summary rather than under-taxing`() = runTest {
        // A 2xx whose body has no `summary` array (shape drift) must throw, not silently return Tax.ZERO (TAX-2).
        enqueue(200, "[]") // valid JSON, not an object -> no summary
        assertFailsWith<IllegalStateException> { calc().calculate(Money.of("10.00"), address()) }
    }

    @Test
    fun `calculate yields no tax when there is no address`() = runTest {
        val tax = calc().calculate(Money.of("50.00"), null)
        assertEquals(bosca.ecommerce.model.Tax.ZERO, tax)
    }

    @Test
    fun `calculate yields no tax for a non-positive amount`() = runTest {
        val tax = calc().calculate(Money.ZERO, address())
        assertEquals(bosca.ecommerce.model.Tax.ZERO, tax)
    }

    @Test
    fun `an unconfigured calculator yields no tax without calling Avalara (both environments)`() = runTest {
        // No baseUrl override -> exercises the prod/sandbox URL selection; blank creds short-circuit before any HTTP.
        val prod = AvalaraTaxCalculator(config(accountId = "").copy(sandbox = false))
        val sandbox = AvalaraTaxCalculator(config(licenseKey = "").copy(sandbox = true))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, prod.calculate(Money.of("100.00"), address()))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, sandbox.calculate(Money.of("100.00"), address()))
    }

    @Test
    fun `calculate throws when Avalara returns an error rather than silently under-taxing`() = runTest {
        enqueue(400, """{"error":{"message":"AddressLocationNotFound"}}""")
        val ex = assertFailsWith<IllegalStateException> { calc().calculate(Money.of("100.00"), address()) }
        assertTrue(ex.message!!.contains("AddressLocationNotFound"))
    }

    @Test
    fun `calculate reports a generic message when the error response lacks a message`() = runTest {
        // No error object at all, then an error object without a message field — both fall back to the generic text.
        val noError = assertFailsWith<IllegalStateException> {
            enqueue(500, """{}""")
            calc().calculate(Money.of("100.00"), address())
        }
        assertTrue(noError.message!!.contains("unknown error"))

        val messagelessError = assertFailsWith<IllegalStateException> {
            enqueue(400, """{"error":{"code":"X"}}""")
            calc().calculate(Money.of("100.00"), address())
        }
        assertTrue(messagelessError.message!!.contains("unknown error"))
    }
}
