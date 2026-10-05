package bosca.ecommerce.shipping.shippo

import bosca.ecommerce.model.Address
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLabel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.TrackingUpdate
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.service.ShippingRateProvider
import bosca.serialization.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shippo carrier shipping provider (multi-carrier aggregator). Ported from the legacy `bosca.shipping.shippo`
 * provider but implemented over the Shippo REST API with the platform's OkHttp outbound HTTP path and
 * **explicit hand-written JSON reads** (no Shippo SDK, no `@Serializable` response DTOs) — so it carries
 * no compiler-generated serializer code and stays GraalVM-native-safe.
 *
 * - [rates]: `POST /shipments` (origin/destination + parcels) → the carrier rate quotes. The opaque rate
 *   [ShippingRate.token] encodes the Shippo rate `object_id` (`shippo:<id>`).
 * - [purchaseLabel]: re-quotes for the *packed* box (the shipment's real parcels), buys the cheapest rate's
 *   label via `POST /transactions`, and returns the carrier + tracking + label URL.
 * - [track]: `GET /tracks/{carrier}/{tracking}` mapped onto the canonical [ShipmentStatus] lifecycle.
 *
 * The API key lives in the provider's [KeyValueProviderConfiguration] (`apiKey`); it is sent as the
 * `Authorization: ShippoToken <key>` header.
 */
class ShippoShippingRateProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled carrier API must not pin the thread
        .build(),
    private val baseUrl: String = "https://api.goshippo.com",
) : ShippingRateProvider {

    override val key: String = "shippo"

    override suspend fun rates(
        provider: ShippingProvider,
        cart: Cart,
        origin: Address?,
        destination: Address?,
        parcels: List<Parcel>,
    ): List<ShippingRate> {
        if (origin == null || destination == null || parcels.isEmpty()) return emptyList()
        val cfg = config(provider)
        val (ok, body) = post(cfg, "/shipments", shipmentBody(origin, destination, parcels))
        if (!ok) return emptyList()
        // Only offer rates in the cart's currency — Shippo can return rates in multiple currencies, and a
        // different-currency amount stamped onto the cart's single-currency shipping line is a silent
        // mismatch. Rates without a currency field are assumed to match.
        return rateObjects(body)
            .filter { val c = it.str("currency"); c == null || c.equals(cart.currency, ignoreCase = true) }
            .mapNotNull { rate ->
                // A missing/unparseable amount is an error condition — drop the rate rather than offering a
                // silent $0 fare that would win "cheapest" and buy a free label.
                val amount = rate.str("amount")?.toBigDecimalOrNull() ?: return@mapNotNull null
                ShippingRate(
                    carrier = rate.str("provider") ?: provider.name,
                    serviceLevel = (rate["servicelevel"] as? JsonObject)?.str("name") ?: "Standard",
                    durationTerms = rate.str("duration_terms"),
                    token = "shippo:${rate.str("object_id").orEmpty()}",
                    amount = Money(amount),
                )
            }
    }

    override suspend fun purchaseLabel(
        provider: ShippingProvider,
        shipment: Shipment,
        origin: Address?,
        destination: Address?,
        parcels: List<Parcel>,
    ): ShipmentLabel? {
        if (origin == null || destination == null || parcels.isEmpty()) return null
        val cfg = config(provider)
        val (quoted, quoteBody) = post(cfg, "/shipments", shipmentBody(origin, destination, parcels))
        if (!quoted) return null
        // The packed box just got re-quoted; buy the cheapest label — but only compare amounts WITHIN a
        // single currency (the quote's), never across currencies, or "cheapest" is meaningless.
        val quotedRates = rateObjects(quoteBody)
        val quoteCurrency = quotedRates.firstOrNull()?.str("currency")
        // Pair each rate with its parsed amount (dropping missing/unparseable ones), within the quote's
        // currency, then buy the genuinely cheapest — never a silent-$0 default.
        val rate = quotedRates
            .filter { val c = it.str("currency"); c == null || c == quoteCurrency }
            .mapNotNull { r -> r.str("amount")?.toBigDecimalOrNull()?.let { r to it } }
            .minByOrNull { it.second }?.first ?: return null
        val rateId = rate.str("object_id") ?: return null
        val (bought, txnBody) = post(
            cfg, "/transactions",
            buildJsonObject {
                put("rate", rateId)
                put("label_file_type", "PDF")
                put("async", false)
            },
        )
        if (!bought) return null
        val txn = parse(txnBody)
        if (!txn.str("status").equals("SUCCESS", ignoreCase = true)) return null
        return ShipmentLabel(
            carrier = rate.str("provider") ?: provider.name,
            tracking = txn.str("tracking_number"),
            labelUrl = txn.str("label_url"),
        )
    }

    override suspend fun track(provider: ShippingProvider, shipment: Shipment): TrackingUpdate? {
        val carrier = (shipment.carrier ?: return null).lowercase().replace(' ', '_')
        val tracking = shipment.tracking ?: return null
        val cfg = config(provider)
        val (ok, body) = get(cfg, "/tracks/$carrier/$tracking")
        if (!ok) return null
        val trackingStatus = parse(body)["tracking_status"] as? JsonObject ?: return null
        val raw = trackingStatus.str("status") ?: return null
        val status = when (raw.uppercase()) {
            "PRE_TRANSIT" -> ShipmentStatus.SHIPPED
            "TRANSIT" -> ShipmentStatus.IN_TRANSIT
            "DELIVERED" -> ShipmentStatus.DELIVERED
            "RETURNED" -> ShipmentStatus.RETURNED
            "FAILURE" -> ShipmentStatus.FAILURE
            else -> return null // UNKNOWN / unrecognized — no advance
        }
        val delivered = if (status == ShipmentStatus.DELIVERED) {
            trackingStatus.str("status_date")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: OffsetDateTime.now()
        } else {
            null
        }
        return TrackingUpdate(status = status, carrierStatus = trackingStatus.str("status_details") ?: raw, delivered = delivered)
    }

    private fun shipmentBody(origin: Address, destination: Address, parcels: List<Parcel>): JsonObject = buildJsonObject {
        put("address_from", addressJson(origin))
        put("address_to", addressJson(destination))
        putJsonArray("parcels") {
            parcels.forEach { parcel ->
                addJsonObject {
                    put("length", parcel.length.toString())
                    put("width", parcel.width.toString())
                    put("height", parcel.height.toString())
                    put("distance_unit", parcel.lengthUnit.distanceUnit())
                    put("weight", parcel.weight.toString())
                    put("mass_unit", parcel.weightUnit.massUnit())
                }
            }
        }
        put("async", false)
    }

    private fun addressJson(address: Address): JsonObject = buildJsonObject {
        put("street1", address.address1)
        address.address2?.let { put("street2", it) }
        put("city", address.city)
        put("state", address.state)
        put("zip", address.zip)
        put("country", address.country)
    }

    private fun LengthUnit.distanceUnit(): String = when (this) {
        LengthUnit.INCHES -> "in"
        LengthUnit.CENTIMETERS -> "cm"
    }

    private fun WeightUnit.massUnit(): String = when (this) {
        WeightUnit.POUNDS -> "lb"
        WeightUnit.KILOGRAMS -> "kg"
    }

    private suspend fun post(cfg: Config, path: String, body: JsonObject): Pair<Boolean, String> {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "ShippoToken ${cfg.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private suspend fun get(cfg: Config, path: String): Pair<Boolean, String> {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "ShippoToken ${cfg.apiKey}")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): Pair<Boolean, String> {
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        return response.use { it.isSuccessful to it.body.string() }
    }

    private fun rateObjects(body: String): List<JsonObject> =
        (parse(body)["rates"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun parse(body: String): JsonObject = (Json.parseToJsonElement(body) as? JsonObject) ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private data class Config(val apiKey: String)

    private fun config(provider: ShippingProvider): Config {
        val values = (provider.configuration as? KeyValueProviderConfiguration)?.values ?: emptyMap()
        val apiKey = values["apiKey"].orEmpty()
        check(apiKey.isNotEmpty()) { "Shippo provider is missing apiKey configuration" }
        return Config(apiKey)
    }

    private companion object {
        private val JSON = "application/json".toMediaType()
    }
}
