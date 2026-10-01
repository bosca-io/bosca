package bosca.analytics.delivery

import bosca.core.analytics.InstallationIdProvider
import bosca.core.preferences.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Owns registration and durable persistence of the Bosca Analytics installation identity. */
class HttpInstallationIdProvider(
    private val collectorUrl: String,
    private val client: HttpClient,
    private val preferences: Preferences,
    private val appId: String? = null,
    private val appVersion: String? = null,
) : InstallationIdProvider {
    /** Creates a provider whose registration request identifies the application build. */
    constructor(
        config: BoscaSinkConfig,
        client: HttpClient,
        preferences: Preferences,
    ) : this(config.url, client, preferences, config.appId, config.appVersion)

    private val mutex = Mutex()

    override suspend fun getOrCreate(): String = mutex.withLock {
        preferences.getString(INSTALLATION_ID_KEY).first()?.takeIf { it.isNotBlank() }?.let {
            return@withLock it
        }
        val response = client.post("${collectorUrl.trimEnd('/')}/installation") {
            accept(ContentType.Application.Json)
            appId?.let { header(BoscaRequestHeaders.APP_ID, it) }
            appVersion?.let { header(BoscaRequestHeaders.APP_VERSION, it) }
        }
        check(response.status.isSuccess()) {
            "Analytics installation registration failed with HTTP ${response.status.value}: ${response.bodyAsText()}"
        }
        val id = JSON.decodeFromString(InstallationResponse.serializer(), response.bodyAsText()).id
        check(id.isNotBlank()) { "Analytics returned an empty installation ID" }
        preferences.setString(INSTALLATION_ID_KEY, id)
        id
    }

    private companion object {
        const val INSTALLATION_ID_KEY = "__iid"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
