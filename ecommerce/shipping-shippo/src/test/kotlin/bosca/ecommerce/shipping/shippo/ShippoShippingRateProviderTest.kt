package bosca.ecommerce.shipping.shippo

import bosca.ecommerce.model.Address
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.WeightUnit
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Shippo REST port (OkHttp): rate quoting, label purchase (re-quote → cheapest → transaction), and tracking mapping. */
@OptIn(ExperimentalUuidApi::class)
class ShippoShippingRateProviderTest {

    private lateinit var server: MockWebServer
    private val bodies = mutableMapOf<String, String>()

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    /** Route responses by Shippo endpoint path (tracks routes by prefix) and record each request body. */
    private fun provider(responses: Map<String, Pair<Int, String>>): ShippoShippingRateProvider {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                bodies[path] = request.body?.utf8() ?: ""
                val match = responses[path] ?: responses.entries.firstOrNull { path.startsWith(it.key) }?.value ?: (404 to "{}")
                return MockResponse.Builder().code(match.first).body(match.second).build()
            }
        }
        return ShippoShippingRateProvider(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    private fun config(apiKey: String = "shippo_test") = ShippingProvider(
        id = UUID.random(), companyId = UUID.random(), name = "Shippo", key = "shippo-1", providerKey = "shippo",
        configuration = KeyValueProviderConfiguration(mapOf("apiKey" to apiKey)),
    )

    private fun cart() = Cart(id = UUID.random(), companyId = UUID.random(), storeId = UUID.random(), expires = OffsetDateTime.now().plusSeconds(3600))

    private fun origin() = Address(address1 = "100 Origin St", address2 = "Dock 4", city = "Oakland", state = "CA", country = "US", zip = "94607")
    private fun destination() = Address(address1 = "200 Dest Ave", city = "Reno", state = "NV", country = "US", zip = "89501")
    private fun parcel() = Parcel(length = 5.0, width = 4.0, height = 3.0, weight = 2.0)

    private fun shipment(carrier: String? = "USPS", tracking: String? = "9400111") = Shipment(
        id = UUID.random(), cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random(),
        fulfillmentCenterId = UUID.random(), status = ShipmentStatus.SHIPPED, carrier = carrier, tracking = tracking,
    )

    private fun bodyAt(path: String): JsonObject = Json.parseToJsonElement(bodies.getValue(path)) as JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(key: String): JsonArray = this[key] as JsonArray

    private val twoRates = """
        {"rates":[
          {"object_id":"rate_hi","provider":"UPS","amount":"12.50","servicelevel":{"name":"Ground","token":"ups_ground"},"duration_terms":"3-5 days"},
          {"object_id":"rate_lo","provider":"USPS","amount":"5.50","servicelevel":{"name":"Priority","token":"usps_priority"},"duration_terms":"2-3 days"}
        ]}
    """.trimIndent()

    // ---- rates ----

    @Test
    fun `rates maps each Shippo rate, encodes the object id as the token, and sends origin, destination and parcels`() = runTest {
        val rates = provider(mapOf("/shipments" to (201 to twoRates)))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))

        assertEquals(2, rates.size)
        val lo = rates.first { it.token == "shippo:rate_lo" }
        assertEquals("USPS", lo.carrier)
        assertEquals("Priority", lo.serviceLevel)
        assertEquals("2-3 days", lo.durationTerms)
        assertEquals(Money.of("5.50"), lo.amount)

        // The request carried both addresses and the parcel with correct unit mapping.
        val sent = bodyAt("/shipments")
        assertEquals("100 Origin St", (sent["address_from"] as JsonObject).str("street1"))
        assertEquals("Dock 4", (sent["address_from"] as JsonObject).str("street2"))
        assertEquals("Reno", (sent["address_to"] as JsonObject).str("city"))
        assertNull((sent["address_to"] as JsonObject).str("street2")) // destination has no address2
        val p = sent.arr("parcels")[0] as JsonObject
        assertEquals("in", p.str("distance_unit"))
        assertEquals("lb", p.str("mass_unit"))
        assertEquals("5.0", p.str("length"))
    }

    @Test
    fun `rates offers only rates in the cart's currency`() = runTest {
        val mixed = """
            {"rates":[
              {"object_id":"r_usd","provider":"USPS","amount":"5.50","currency":"USD"},
              {"object_id":"r_eur","provider":"DHL","amount":"3.00","currency":"EUR"}
            ]}
        """.trimIndent()
        val rates = provider(mapOf("/shipments" to (201 to mixed)))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))

        assertEquals(1, rates.size) // the EUR rate is dropped — cart() is USD
        assertEquals("USPS", rates.first().carrier)
    }

    @Test
    fun `rates maps metric units to cm and kg`() = runTest {
        provider(mapOf("/shipments" to (201 to twoRates)))
            .rates(config(), cart(), origin(), destination(), listOf(parcel().copy(lengthUnit = LengthUnit.CENTIMETERS, weightUnit = WeightUnit.KILOGRAMS)))
        val p = bodyAt("/shipments").arr("parcels")[0] as JsonObject
        assertEquals("cm", p.str("distance_unit"))
        assertEquals("kg", p.str("mass_unit"))
    }

    @Test
    fun `rates falls back to provider name, Standard service, and empty token on sparse rate fields`() = runTest {
        // rate[0]: servicelevel absent (cast to null). rate[1]: servicelevel present but no name. Both lack
        // provider, object_id and duration_terms — exercising every per-field fallback. A valid amount is
        // required now (a missing amount drops the rate — see the next test).
        val sparse = """{"rates":[{"amount":"4.00"},{"servicelevel":{},"amount":"6.00"}]}"""
        val rates = provider(mapOf("/shipments" to (201 to sparse)))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))
        assertEquals(2, rates.size)
        assertTrue(rates.all { it.carrier == "Shippo" })          // provider.name fallback
        assertTrue(rates.all { it.serviceLevel == "Standard" })   // servicelevel name fallback (absent + empty object)
        assertTrue(rates.all { it.durationTerms == null })
        assertTrue(rates.all { it.token == "shippo:" })           // object_id fallback
    }

    @Test
    fun `rates drops a rate with a missing or unparseable amount`() = runTest {
        val body = """{"rates":[
          {"object_id":"ok","provider":"USPS","amount":"5.00"},
          {"object_id":"no_amount","provider":"UPS"},
          {"object_id":"bad_amount","provider":"DHL","amount":"free"}
        ]}"""
        val rates = provider(mapOf("/shipments" to (201 to body)))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))
        assertEquals(1, rates.size) // only the valid-amount rate is offered — no silent $0
        assertEquals("USPS", rates.first().carrier)
    }

    @Test
    fun `rates returns nothing when the body is not a JSON object`() = runTest {
        val rates = provider(mapOf("/shipments" to (201 to "[]")))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))
        assertTrue(rates.isEmpty())
    }

    @Test
    fun `rates returns nothing when origin, destination or parcels are missing`() = runTest {
        val p = provider(emptyMap())
        assertTrue(p.rates(config(), cart(), null, destination(), listOf(parcel())).isEmpty())
        assertTrue(p.rates(config(), cart(), origin(), null, listOf(parcel())).isEmpty())
        assertTrue(p.rates(config(), cart(), origin(), destination(), emptyList()).isEmpty())
    }

    @Test
    fun `rates returns nothing when Shippo rejects the request`() = runTest {
        val rates = provider(mapOf("/shipments" to (401 to """{"detail":"bad token"}""")))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))
        assertTrue(rates.isEmpty())
    }

    @Test
    fun `rates returns nothing when the response has no rates array`() = runTest {
        val rates = provider(mapOf("/shipments" to (201 to """{"status":"QUEUED"}""")))
            .rates(config(), cart(), origin(), destination(), listOf(parcel()))
        assertTrue(rates.isEmpty())
    }

    @Test
    fun `a missing apiKey is a configuration error`() = runTest {
        val p = provider(mapOf("/shipments" to (201 to twoRates)))
        assertFailsWith<IllegalStateException> { p.rates(config(apiKey = ""), cart(), origin(), destination(), listOf(parcel())) }
        // Also covers a non-keyValue configuration (no apiKey present).
        val noKey = config().copy(configuration = EmptyProviderConfiguration)
        assertFailsWith<IllegalStateException> { p.rates(noKey, cart(), origin(), destination(), listOf(parcel())) }
    }

    // ---- purchaseLabel ----

    @Test
    fun `purchaseLabel re-quotes, buys the cheapest rate's label, and returns carrier, tracking and label url`() = runTest {
        val txn = """{"status":"SUCCESS","tracking_number":"1Z-LO","label_url":"https://labels/lo.pdf"}"""
        val label = provider(mapOf("/shipments" to (201 to twoRates), "/transactions" to (201 to txn)))
            .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel()))

        assertNotNull(label)
        assertEquals("USPS", label.carrier)           // the cheapest rate's provider
        assertEquals("1Z-LO", label.tracking)
        assertEquals("https://labels/lo.pdf", label.labelUrl)
        // It bought the cheapest rate (rate_lo), as a synchronous PDF transaction.
        val sent = bodyAt("/transactions")
        assertEquals("rate_lo", sent.str("rate"))
        assertEquals("PDF", sent.str("label_file_type"))
    }

    @Test
    fun `purchaseLabel returns null when origin, destination or parcels are missing`() = runTest {
        val p = provider(emptyMap())
        assertNull(p.purchaseLabel(config(), shipment(), null, destination(), listOf(parcel())))
        assertNull(p.purchaseLabel(config(), shipment(), origin(), null, listOf(parcel())))
        assertNull(p.purchaseLabel(config(), shipment(), origin(), destination(), emptyList()))
    }

    @Test
    fun `purchaseLabel returns null when the re-quote fails or yields no rates`() = runTest {
        assertNull(
            provider(mapOf("/shipments" to (500 to "{}")))
                .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel())),
        )
        assertNull(
            provider(mapOf("/shipments" to (201 to """{"rates":[]}""")))
                .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel())),
        )
    }

    @Test
    fun `purchaseLabel returns null when the chosen rate has no object id`() = runTest {
        // Has a valid amount (so it isn't dropped) but no object_id → cannot buy a label.
        val label = provider(mapOf("/shipments" to (201 to """{"rates":[{"provider":"USPS","amount":"5.00"}]}""")))
            .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel()))
        assertNull(label)
    }

    @Test
    fun `purchaseLabel falls back to the provider name when the bought rate omits its carrier`() = runTest {
        val txn = """{"status":"SUCCESS","tracking_number":"T1","label_url":"https://l/1.pdf"}"""
        val label = provider(mapOf("/shipments" to (201 to """{"rates":[{"object_id":"r1","amount":"5.00"}]}"""), "/transactions" to (201 to txn)))
            .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel()))
        assertNotNull(label)
        assertEquals("Shippo", label.carrier) // rate had no provider → provider.name
        assertEquals("T1", label.tracking)
    }

    @Test
    fun `purchaseLabel returns null when the transaction call fails`() = runTest {
        val label = provider(mapOf("/shipments" to (201 to twoRates), "/transactions" to (402 to "{}")))
            .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel()))
        assertNull(label)
    }

    @Test
    fun `purchaseLabel returns null when the transaction does not succeed`() = runTest {
        val txn = """{"status":"ERROR","messages":[{"text":"address invalid"}]}"""
        val label = provider(mapOf("/shipments" to (201 to twoRates), "/transactions" to (201 to txn)))
            .purchaseLabel(config(), shipment(), origin(), destination(), listOf(parcel()))
        assertNull(label)
    }

    // ---- track ----

    @Test
    fun `track maps PRE_TRANSIT to SHIPPED`() = runTest {
        val u = track("PRE_TRANSIT")
        assertEquals(ShipmentStatus.SHIPPED, u?.status)
    }

    @Test
    fun `track maps TRANSIT to IN_TRANSIT and keeps the carrier wording`() = runTest {
        val body = """{"tracking_status":{"status":"TRANSIT","status_details":"In transit to next facility"}}"""
        val u = provider(mapOf("/tracks" to (200 to body))).track(config(), shipment(carrier = "USPS Priority"))
        assertEquals(ShipmentStatus.IN_TRANSIT, u?.status)
        assertEquals("In transit to next facility", u?.carrierStatus)
        assertNull(u?.delivered)
        // The carrier name is slugged into a Shippo carrier token (lowercased, spaces -> underscores).
        assertTrue(bodies.keys.any { it == "/tracks/usps_priority/9400111" })
    }

    @Test
    fun `track maps DELIVERED and parses the delivery timestamp`() = runTest {
        val body = """{"tracking_status":{"status":"DELIVERED","status_date":"2026-06-10T12:00:00Z"}}"""
        val u = provider(mapOf("/tracks" to (200 to body))).track(config(), shipment())
        assertEquals(ShipmentStatus.DELIVERED, u?.status)
        assertEquals(OffsetDateTime.parse("2026-06-10T12:00:00Z"), u?.delivered)
    }

    @Test
    fun `track falls back to now when DELIVERED has a missing or unparseable date`() = runTest {
        val missing = provider(mapOf("/tracks" to (200 to """{"tracking_status":{"status":"DELIVERED"}}"""))).track(config(), shipment())
        assertNotNull(missing?.delivered)
        val garbage = provider(mapOf("/tracks" to (200 to """{"tracking_status":{"status":"DELIVERED","status_date":"not-a-date"}}"""))).track(config(), shipment())
        assertNotNull(garbage?.delivered)
    }

    @Test
    fun `track maps RETURNED and FAILURE`() = runTest {
        assertEquals(ShipmentStatus.RETURNED, track("RETURNED")?.status)
        assertEquals(ShipmentStatus.FAILURE, track("FAILURE")?.status)
    }

    @Test
    fun `track returns null for an unknown carrier status`() = runTest {
        assertNull(track("UNKNOWN"))
    }

    @Test
    fun `track returns null when the shipment lacks a carrier or tracking number`() = runTest {
        val p = provider(emptyMap())
        assertNull(p.track(config(), shipment(carrier = null)))
        assertNull(p.track(config(), shipment(tracking = null)))
    }

    @Test
    fun `track returns null when Shippo fails or omits the tracking status`() = runTest {
        assertNull(provider(mapOf("/tracks" to (404 to "{}"))).track(config(), shipment()))
        assertNull(provider(mapOf("/tracks" to (200 to """{"carrier":"usps"}"""))).track(config(), shipment()))
        assertNull(provider(mapOf("/tracks" to (200 to """{"tracking_status":{"status_details":"x"}}"""))).track(config(), shipment()))
    }

    private suspend fun track(status: String) =
        provider(mapOf("/tracks" to (200 to """{"tracking_status":{"status":"$status"}}"""))).track(config(), shipment())

    // ---- DI registration ----

    @Test
    fun `the configuration registers the provider under the shippo key`() {
        assertEquals("shippo", ShippoConfiguration().shippoShippingRateProvider().key)
    }
}
