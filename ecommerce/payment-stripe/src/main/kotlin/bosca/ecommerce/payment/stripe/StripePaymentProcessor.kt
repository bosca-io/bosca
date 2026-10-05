package bosca.ecommerce.payment.stripe

import bosca.ecommerce.model.CreditCard
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PaymentResult
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.ecommerce.service.PaymentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Stripe payment gateway. Ported from the legacy `bosca.stripe` provider but implemented over the
 * Stripe REST API with the platform's OkHttp outbound HTTP path and **explicit hand-written JSON
 * reads** (no `stripe-java` SDK, no `@Serializable` response DTOs) — so it carries no
 * compiler-generated serializer code.
 *
 * Charges flow: a single-use [token] (from Stripe.js) is charged directly; a raw [creditCard] is
 * tokenized first (`/v1/tokens`). When `save` is set, a Customer is created from the source and the
 * Customer id is returned as the reusable [SavedPaymentMethod] token, which [charge] re-bills
 * (subscription renewals). The secret key lives in the provider's [KeyValueProviderConfiguration]
 * (`secretKey`); the charge currency is the store's, passed per call. Card data is transient — never
 * persisted or logged.
 */
class StripePaymentProcessor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled gateway must not pin the thread
        .build(),
    private val baseUrl: String = "https://api.stripe.com",
) : PaymentProcessor {

    override val key: String = "stripe"

    override suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: CreditCard?, save: Boolean, idempotencyKey: String?): PaymentResult {
        val cfg = config(provider)
        val source = token ?: tokenize(cfg, creditCard) ?: return failure("Stripe requires a single-use token or raw card details")
        val cents = minorUnits(amount, currency)
        val cur = currency.lowercase()
        if (!save) return chargeSource(cfg, "source" to source, cents, cur, idempotencyKey)
        val customer = createCustomer(cfg, source) ?: return failure("Stripe customer creation failed")
        val result = chargeSource(cfg, "customer" to customer, cents, cur, idempotencyKey)
        return if (result.complete) result.copy(saved = SavedPaymentMethod(providerKey = key, token = customer)) else result
    }

    override suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String?): PaymentResult {
        val cfg = config(provider)
        // Stripe derives the refund currency from the original charge, so [currency] isn't sent here.
        val form = FormBody.Builder()
        if (originalTransactionId != null) form.add("charge", originalTransactionId)
        form.add("amount", minorUnits(amount, currency).toString())
        val (ok, body) = post(cfg, "/v1/refunds", form.build(), idempotencyKey)
        if (!ok) return failure(stripeError(body), transportFailure = true)
        val obj = parse(body)
        val status = obj.str("status")
        val complete = status == "succeeded" || status == "pending"
        return PaymentResult(complete = complete, confirmed = status == "succeeded", status = status, transactionId = obj.str("id"), providerId = key)
    }

    override suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String?): PaymentResult {
        val cfg = config(provider)
        return chargeSource(cfg, "customer" to saved.token, minorUnits(amount, currency), currency.lowercase(), idempotencyKey)
    }

    private suspend fun chargeSource(cfg: Config, source: Pair<String, String>, cents: Long, currency: String, idempotencyKey: String?): PaymentResult {
        val (ok, body) = post(
            cfg, "/v1/charges",
            FormBody.Builder().add(source.first, source.second).add("amount", cents.toString()).add("currency", currency).build(),
            idempotencyKey,
        )
        if (!ok) return failure(stripeError(body), transportFailure = true)
        val obj = parse(body)
        val paid = obj.bool("paid")
        val failureMessage = obj.str("failure_message")
        return PaymentResult(
            complete = paid,
            confirmed = paid,
            status = obj.str("status"),
            // Record the charge id (`ch_…`) — refund sends it as Stripe's `charge` param; `payment_intent`
            // (`pi_…`) is a different namespace Stripe's refund would reject.
            transactionId = obj.str("id") ?: obj.str("payment_intent"),
            providerId = key,
            message = failureMessage,
            error = if (paid) null else failureMessage,
        )
    }

    private suspend fun tokenize(cfg: Config, card: CreditCard?): String? {
        if (card == null) return null
        val (ok, body) = post(
            cfg, "/v1/tokens",
            FormBody.Builder()
                .add("card[number]", card.number)
                .add("card[exp_month]", card.expirationMonth.toString())
                .add("card[exp_year]", card.expirationYear.toString())
                .add("card[cvc]", card.cvv)
                .add("card[name]", card.name)
                .build(),
        )
        return if (ok) parse(body).str("id") else null
    }

    private suspend fun createCustomer(cfg: Config, source: String): String? {
        val (ok, body) = post(cfg, "/v1/customers", FormBody.Builder().add("source", source).build())
        return if (ok) parse(body).str("id") else null
    }

    private suspend fun post(cfg: Config, path: String, form: FormBody, idempotencyKey: String? = null): Pair<Boolean, String> {
        val builder = Request.Builder().url("$baseUrl$path").addHeader("Authorization", "Bearer ${cfg.secretKey}").post(form)
        if (idempotencyKey != null) builder.addHeader("Idempotency-Key", idempotencyKey)
        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(builder.build()).execute() }
            response.use { it.isSuccessful to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            // A connection/timeout failure (e.g. the callTimeout ceiling) — surface as a non-ok (transport) result
            // so the caller maps it to a transportFailure rather than letting it unwind the renewal batch.
            false to ""
        }
    }

    // A 2xx body that is empty / not a JSON object (proxy/HTML/partial response) must NOT throw — that
    // would unwind the caller's transaction after Stripe may have already charged. Fall back to an empty
    // object so the result maps to a clean (recorded) failure instead of an uncaught exception.
    private fun parse(body: String): JsonObject =
        runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun stripeError(body: String): String =
        runCatching { (parse(body)["error"] as? JsonObject)?.str("message") }.getOrNull() ?: "Stripe request failed"

    private fun failure(message: String, transportFailure: Boolean = false) =
        PaymentResult(complete = false, confirmed = false, status = "error", providerId = key, message = message, error = message, transportFailure = transportFailure)

    private data class Config(val secretKey: String)

    private fun config(provider: PaymentProvider): Config {
        val values = (provider.configuration as? KeyValueProviderConfiguration)?.values ?: emptyMap()
        val secretKey = values["secretKey"].orEmpty()
        check(secretKey.isNotEmpty()) { "Stripe provider is missing secretKey configuration" }
        return Config(secretKey)
    }

    // The charge amount in the currency's smallest unit. Most currencies are 2-decimal, but zero-decimal
    // (JPY, KRW, …) and 3-decimal (BHD, KWD, …) currencies use a different exponent — hardcoding ×100 would
    // overcharge JPY 100×. Exceptions per the ISO-4217 / Stripe minor-unit table.
    private fun minorUnits(amount: Money, currency: String): Long {
        val exponent = when (currency.uppercase()) {
            in ZERO_DECIMAL_CURRENCIES -> 0
            in THREE_DECIMAL_CURRENCIES -> 3
            else -> 2
        }
        return amount.amount.movePointRight(exponent).setScale(0, RoundingMode.HALF_EVEN).toLong()
    }

    private companion object {
        private val ZERO_DECIMAL_CURRENCIES =
            setOf("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF")
        private val THREE_DECIMAL_CURRENCIES = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")
    }
}
