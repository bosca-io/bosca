package bosca.ecommerce.tax.avalara

import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax
import bosca.ecommerce.service.TaxCalculator
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
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Real jurisdiction tax calculator backed by Avalara AvaTax (REST), replacing the per-state
 * [bosca.ecommerce.service.DefaultTaxCalculator] when this module is included. Implemented over OkHttp
 * with **explicit hand-written JSON reads** (no AvaTax SDK, no `@Serializable` response DTOs) so it is
 * GraalVM-native-safe and carries no compiler-generated serializer code.
 *
 * Each call posts a non-committing `SalesOrder` transaction (an estimate — it never records a document
 * in AvaTax) with the ship-from ([AvalaraConfig.origin]) and ship-to (the cart [CartAddress]) addresses
 * and the taxable amount as a single line, then maps the returned per-jurisdiction `summary` onto the
 * [Tax] components. Credentials travel as HTTP Basic auth (`accountId:licenseKey`).
 *
 * Failure policy: an unconfigured calculator (missing credentials) yields [Tax.ZERO] like the default
 * provider; a configured calculator that gets a non-success response from AvaTax **throws** rather than
 * silently under-taxing the cart.
 */
class AvalaraTaxCalculator(
    private val config: AvalaraConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled AvaTax call must not pin the thread
        .build(),
    baseUrl: String? = null,
    private val today: () -> String = { LocalDate.now().toString() },
) : TaxCalculator {

    private val baseUrl: String = baseUrl ?: if (config.sandbox) "https://sandbox.rest.avatax.com" else "https://rest.avatax.com"

    override suspend fun calculate(taxableAmount: Money, address: CartAddress?): Tax {
        if (!config.configured || address == null || !taxableAmount.isPositive) return Tax.ZERO

        val body = buildJsonObject {
            put("type", "SalesOrder") // estimate only — does not record a committed document in AvaTax
            put("companyCode", config.companyCode)
            put("date", today())
            put("customerCode", "0")
            putJsonObject("addresses") {
                put("shipFrom", addressJson(config.origin))
                put("shipTo", cartAddressJson(address))
            }
            putJsonArray("lines") {
                addJsonObject {
                    put("number", "1")
                    put("quantity", 1)
                    put("amount", taxableAmount.amount)
                    put("taxCode", "P0000000") // generic tangible personal property
                }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/api/v2/transactions/create")
            .addHeader("Authorization", basicAuth())
            .post(body.toString().toRequestBody(JSON))
            .build()
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val (ok, raw) = response.use { it.isSuccessful to it.body.string() }
        val json = Json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
        check(ok) { "Avalara tax request failed: ${avalaraError(json)}" }
        // A 2xx whose body lacks a usable `summary` (shape drift / renamed field) must fail loud, not return
        // Tax.ZERO — silently under-taxing the cart is exactly what this provider's fail-loud policy forbids.
        val summary = json["summary"] as? JsonArray
            ?: error("Avalara returned success with no tax summary — refusing to under-tax the cart")
        return taxFrom(summary)
    }

    /** Sum the AvaTax `summary` rows into the [Tax] jurisdiction components. */
    private fun taxFrom(summary: JsonArray): Tax {
        var country = Money.ZERO
        var state = Money.ZERO
        var city = Money.ZERO
        var district = Money.ZERO
        summary.filterIsInstance<JsonObject>().forEach { row ->
            val amount = row.money("tax")
            when (row.str("jurisType")?.uppercase()) {
                "COUNTRY" -> country += amount
                "STATE" -> state += amount
                "CITY" -> city += amount
                else -> district += amount // County / Special / anything else
            }
        }
        return Tax.of(country = country, state = state, city = city, district = district)
    }

    private fun addressJson(address: AvalaraAddress): JsonObject = buildJsonObject {
        put("line1", address.line1)
        put("city", address.city)
        put("region", address.region)
        put("postalCode", address.postalCode)
        put("country", address.country)
    }

    private fun cartAddressJson(address: CartAddress): JsonObject = buildJsonObject {
        put("line1", address.address1)
        put("city", address.city)
        put("region", address.state)
        put("postalCode", address.zip)
        put("country", address.country)
    }

    private fun basicAuth(): String =
        "Basic " + Base64.getEncoder().encodeToString("${config.accountId}:${config.licenseKey}".toByteArray())

    private fun avalaraError(json: JsonObject): String =
        ((json["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "unknown error"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.money(key: String): Money = (this[key] as? JsonPrimitive)?.contentOrNull?.let { Money.of(it) } ?: Money.ZERO

    private companion object {
        private val JSON = "application/json".toMediaType()
    }
}
