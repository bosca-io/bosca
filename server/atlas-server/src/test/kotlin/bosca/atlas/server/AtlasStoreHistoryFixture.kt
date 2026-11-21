package bosca.atlas.server

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private val storeHistoryFixtureHttp = HttpClient.newHttpClient()

/** Replaces only the external analytics-history execution; other analytics operations keep their real service. */
internal class AtlasStoreHistoryFixture(
    private val actual: AnalyticsQueryExecutionService,
    private val origin: String,
) : AnalyticsQueryExecutionService by actual {
    override suspend fun execute(key: String, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        if (key != "store-release-telemetry") return actual.execute(key, parameters)
        fun argument(name: String) = URLEncoder.encode(parameters.single { it.parameter == name }.value.jsonPrimitive.content, StandardCharsets.UTF_8)
        val response = withContext(Dispatchers.IO) { storeHistoryFixtureHttp.send(
            HttpRequest.newBuilder(URI.create("$origin/fixture/observations?application=${argument("application_id")}&version=${argument("app_version")}")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ) }
        check(response.statusCode() == 200) { "Store history fixture read failed: ${response.statusCode()}" }
        return AnalyticsQueryResponse(Json.parseToJsonElement(response.body()).jsonArray.toList())
    }
}
