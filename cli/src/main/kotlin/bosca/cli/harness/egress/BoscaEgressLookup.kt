package bosca.cli.harness.egress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.time.Duration

/** Asks Bosca (`GET {boscaUrl}/api/v1/kit/egress`) which destinations a runtime session may reach. */
internal class BoscaEgressLookup(boscaUrl: String, private val client: OkHttpClient = defaultClient()) : EgressLookup {
    private val endpoint = (boscaUrl.trimEnd('/') + "/api/v1/kit/egress").toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(credentials: EgressCredentials): EgressLookupResult {
        val request = Request.Builder().url(endpoint).get()
            .header("X-Bosca-Kit-Session", credentials.session)
            .header("X-Bosca-Kit-Egress", credentials.token)
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                when {
                    response.code == 200 -> {
                        val body = withContext(Dispatchers.IO) { response.body.string() }
                        val destinations = json.decodeFromString(EgressLookupResponse.serializer(), body).destinations
                        EgressLookupResult.Approved(destinations.mapNotNull { value ->
                            EgressDestination.parseOrNull(value).also { if (it == null) System.err.println("Egress lookup returned an invalid destination; it is ignored") }
                        }.toSet())
                    }
                    response.code >= 500 -> {
                        System.err.println("Egress lookup failed: HTTP ${response.code}")
                        EgressLookupResult.Unavailable
                    }
                    else -> EgressLookupResult.Denied
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            System.err.println("Egress lookup failed: ${failure::class.simpleName}: ${failure.message}")
            EgressLookupResult.Unavailable
        } catch (failure: SerializationException) {
            System.err.println("Egress lookup returned an unreadable response: ${failure.message}")
            EgressLookupResult.Unavailable
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(10))
            .followRedirects(false)
            .build()
    }
}
