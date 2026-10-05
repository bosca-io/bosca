package bosca.ecommerce.payment.bluepay

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
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * BluePay payment gateway (a PAN-processing gateway). Ported from the legacy `bosca.bluepay`
 * provider: a form-encoded POST to BluePay's bp20post endpoint with an MD5 "tamper-proof seal" over
 * the secret + ordered fields. Uses the platform's OkHttp outbound HTTP path (no SDK).
 *
 * Credentials live in the provider's [KeyValueProviderConfiguration]: `accountId`, `secretKey`,
 * optional `mode` (TEST|LIVE, default TEST). The raw [CreditCard] is required and is never persisted
 * or logged — only the returned transaction id is recorded.
 */
class BluePayPaymentProcessor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS) // overall ceiling — a stalled gateway must not pin the thread
        .build(),
    private val endpoint: String = "https://secure.bluepay.com/interfaces/bp20post",
) : PaymentProcessor {

    override val key: String = "bluepay"

    // BluePay is a USD-only gateway: a non-USD charge is rejected here rather than silently charged in USD
    // while the ledger records the store currency. Refund/rebill inherit the validated original's currency.
    override suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: CreditCard?, save: Boolean, idempotencyKey: String?): PaymentResult {
        if (currency.uppercase() != "USD") return PaymentResult(complete = false, status = "ERROR", error = "BluePay supports USD only (got $currency)")
        val cfg = config(provider)
        val card = creditCard ?: error("BluePay requires raw card details (it is a PAN-processing gateway)")
        val amountStr = amountString(amount)
        val (firstName, lastName) = splitName(card.name)
        // Default TPS ordering for a SALE (matches the legacy provider): secret, account, type, amount, name, pan.
        val seal = md5Hex("${cfg.secretKey}${cfg.accountId}$TRANS_SALE$amountStr$firstName${card.number}")
        val form = FormBody.Builder()
            .add("ACCOUNT_ID", cfg.accountId)
            .add("TAMPER_PROOF_SEAL", seal)
            .add("TRANS_TYPE", TRANS_SALE)
            .add("PAYMENT_TYPE", "CREDIT")
            .add("AMOUNT", amountStr)
            .add("MODE", cfg.mode)
            .add("PAYMENT_ACCOUNT", card.number)
            .add("CARD_CVV2", card.cvv)
            .add("CARD_EXPIRE", expirationDate(card))
            .add("NAME1", firstName)
        if (lastName != null) form.add("NAME2", lastName)
        if (idempotencyKey != null) form.add("ORDER_ID", idempotencyKey) // BluePay dedupes a re-post with the same ORDER_ID
        return toResult(post(form.build()), save)
    }

    override suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String?): PaymentResult {
        val cfg = config(provider)
        val amountStr = amountString(amount)
        val seal = md5Hex("${cfg.secretKey}${cfg.accountId}$TRANS_REFUND$amountStr")
        val form = FormBody.Builder()
            .add("ACCOUNT_ID", cfg.accountId)
            .add("TPS_DEF", "ACCOUNT_ID TRANS_TYPE AMOUNT")
            .add("TAMPER_PROOF_SEAL", seal)
            .add("TRANS_TYPE", TRANS_REFUND)
            .add("AMOUNT", amountStr)
            .add("MASTER_ID", originalTransactionId ?: "")
            .add("MODE", cfg.mode)
            .build()
        return toResult(post(form), save = false)
    }

    override suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String?): PaymentResult {
        val cfg = config(provider)
        val amountStr = amountString(amount)
        // Re-bill the stored account by referencing the original transaction (BluePay MASTER_ID rebill).
        val seal = md5Hex("${cfg.secretKey}${cfg.accountId}$TRANS_SALE$amountStr")
        val form = FormBody.Builder()
            .add("ACCOUNT_ID", cfg.accountId)
            .add("TPS_DEF", "ACCOUNT_ID TRANS_TYPE AMOUNT")
            .add("TAMPER_PROOF_SEAL", seal)
            .add("TRANS_TYPE", TRANS_SALE)
            .add("AMOUNT", amountStr)
            .add("MASTER_ID", saved.token)
            .add("MODE", cfg.mode)
        if (idempotencyKey != null) form.add("ORDER_ID", idempotencyKey) // dedupe a redelivered renewal re-bill
        return toResult(post(form.build()), save = false)
    }

    /** Returns `(transportOk, parameters)` — `transportOk = false` on a non-2xx or a connection/timeout failure. */
    private suspend fun post(form: FormBody): Pair<Boolean, Map<String, String>> {
        val request = Request.Builder().url(endpoint).addHeader("User-Agent", "Bosca/1.0").post(form).build()
        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            // A non-2xx body must not be parsed as a transaction outcome (a WAF/HTML/timeout page lacks STATUS
            // and would otherwise read as an indistinct decline). Surface the HTTP status as a distinct ERROR
            // result so a refund's `check(result.complete)` fails loudly with context and
            // carries transportFailure so renewal dunning doesn't count it as a decline.
            response.use {
                if (!it.isSuccessful) false to mapOf("STATUS" to "E", "MESSAGE" to "BluePay HTTP error ${it.code}")
                else true to parseResponse(it.body?.string() ?: "")
            }
        } catch (e: IOException) {
            // A connection/timeout failure (e.g. the callTimeout ceiling) is a transport failure, not a decline.
            false to mapOf("STATUS" to "E", "MESSAGE" to "BluePay transport error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun toResult(transport: Pair<Boolean, Map<String, String>>, save: Boolean): PaymentResult {
        val (transportOk, parameters) = transport
        // Response-trust model: the MD5 TAMPER_PROOF_SEAL is an OUTBOUND request integrity check;
        // BluePay's bp20post response is a plain form-encoded body with no verifiable response seal to
        // recompute, so the parsed STATUS/TRANS_ID are trusted as-is. Integrity rests on TLS to
        // secure.bluepay.com (a tampered body implies a broken/MITM'd TLS channel, out of this layer's scope).
        // This matches the legacy provider; it is not a regression. If BluePay ever documents a response-side
        // seal, recompute and compare it here before trusting `approved`.
        val status = when (parameters["STATUS"]) {
            "1" -> "APPROVED"
            "2" -> "DECLINE"
            else -> "ERROR"
        }
        val approved = status == "APPROVED"
        val transactionId = parameters["TRANS_ID"]
        return PaymentResult(
            complete = approved,
            confirmed = approved,
            status = status,
            transactionId = transactionId,
            providerId = key,
            avs = parameters["AVS"],
            type = parameters["TRANS_TYPE"],
            message = parameters["MESSAGE"],
            error = if (approved) null else parameters["MESSAGE"],
            // A non-2xx/timeout (not a STATUS=2 decline) is a transport failure — renewal dunning skips it.
            transportFailure = !transportOk,
            saved = if (save && approved && transactionId != null) SavedPaymentMethod(providerKey = key, token = transactionId) else null,
        )
    }

    private data class Config(val accountId: String, val secretKey: String, val mode: String)

    private fun config(provider: PaymentProvider): Config {
        val values = (provider.configuration as? KeyValueProviderConfiguration)?.values ?: emptyMap()
        val accountId = values["accountId"].orEmpty()
        val secretKey = values["secretKey"].orEmpty()
        check(accountId.isNotEmpty() && secretKey.isNotEmpty()) { "BluePay provider is missing accountId/secretKey configuration" }
        val mode = values["mode"]?.uppercase()?.takeIf { it == "LIVE" } ?: "TEST"
        return Config(accountId, secretKey, mode)
    }

    private fun amountString(amount: Money): String =
        amount.amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    private fun splitName(name: String): Pair<String, String?> {
        val parts = name.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.size <= 1) return name.trim() to null
        return parts.dropLast(1).joinToString(" ") to parts.last()
    }

    private fun expirationDate(card: CreditCard): String {
        val month = if (card.expirationMonth < 10) "0${card.expirationMonth}" else card.expirationMonth.toString()
        val year = if (card.expirationYear > 2000) card.expirationYear - 2000 else card.expirationYear
        val yy = if (year < 10) "0$year" else year.toString()
        return "$month$yy"
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return String.format("%0" + (digest.size shl 1) + "x", BigInteger(1, digest))
    }

    private fun parseResponse(body: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in body.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            result[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return result
    }

    private companion object {
        const val TRANS_SALE = "SALE"
        const val TRANS_REFUND = "REFUND"
    }
}
