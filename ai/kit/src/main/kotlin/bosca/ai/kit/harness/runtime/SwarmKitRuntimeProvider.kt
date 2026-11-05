package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The existing runtime sweep drives this durable intent; only the manager can access Docker. */
class SwarmKitRuntimeProvider(private val repository: KitSwarmRepository, private val application: BoscaApplication, private val json: Json) : KitRuntimeProvider {
    private val client = HttpClient.newHttpClient()

    override suspend fun ensure(configuration: KitHarnessConfiguration, key: String, environment: Map<String, String>, secrets: KitRuntimeSecrets): UUID {
        val request = KitSwarmRequest(configuration.profile, key, configuration.image, environment, secrets)
        val intent = repository.create(KitSwarmDispatch(UUID.random(), secrets.keyId, configuration.profile, key, request))
        check(intent.request == request && !intent.stopping) { "Swarm generation differs from its immutable dispatch" }
        return intent.id
    }

    override suspend fun status(configuration: KitHarnessConfiguration, reference: UUID): KitRuntimeExecution? {
        val intent = requireNotNull(repository.get(reference)) { "Swarm runtime intent is missing" }
        if (intent.stopping) return null
        if (intent.created.plusSeconds(configuration.startupSeconds).isBefore(OffsetDateTime.now()))
            return KitRuntimeExecution(intent.created, true, null, null, null, "The Swarm runtime did not become ready before its startup deadline. Queued messages have been preserved.")
        val request = json.encodeToString(KitSwarmRequest.serializer(), requireNotNull(intent.request))
        val observation = try { requireNotNull(call(reference, "PUT", request, KitSwarmExecution.serializer())) }
        catch (error: KitSwarmControllerException) {
            if (error.status !in 400..499) throw error
            return KitRuntimeExecution(intent.created, true, null, null, null, error.message)
        }
        check(repository.materialized(reference) == 1)
        return KitRuntimeExecution(intent.created, observation.terminal, observation.endpoint, observation.image, observation.imageId)
    }

    override suspend fun stop(configuration: KitHarnessConfiguration, reference: UUID) { check(repository.stop(reference) == 1) }

    override suspend fun isStopped(configuration: KitHarnessConfiguration, reference: UUID): Boolean {
        val intent = repository.get(reference) ?: return false
        if (intent.stopped) return true
        if (intent.stopping) {
            if (call(reference, "POST", json.encodeToString(KitSwarmRequest.serializer(), requireNotNull(intent.request)), Boolean.serializer(), "/stop") != true) return false
            check(repository.stopped(reference) == 1)
            return true
        }
        if (!intent.materialized) return false
        // Loss of manager observation does not prove that a healthy, admitted worker has stopped.
        val observation = try { call(reference, "GET", null, KitSwarmExecution.serializer(), missing = true) }
        catch (error: java.io.IOException) { currentCoroutineContext().ensureActive(); log.warn("Swarm observation unavailable; retaining the active generation", error); return false }
        catch (error: KitSwarmControllerException) { log.warn("Swarm observation unavailable: HTTP {}", error.status); return false }
        return if (observation == null) intent.materialized else observation.terminal
    }

    private suspend fun <T> call(reference: UUID, method: String, body: String?, serializer: KSerializer<T>, suffix: String = "", missing: Boolean = false): T? = runInterruptible(Dispatchers.IO) {
        val config = application.environment.config
        val endpoint = config.property("kit.swarm.controller.url").getString().trimEnd('/')
        val token = config.property("kit.swarm.controller.token").getString()
        val builder = HttpRequest.newBuilder(URI.create("$endpoint/v1/runtimes/$reference$suffix"))
            .timeout(Duration.ofSeconds(40)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404 && missing) return@runInterruptible null
        if (response.statusCode() != 200) throw KitSwarmControllerException(response.statusCode())
        json.decodeFromString(serializer, response.body())
    }

    companion object { private val log = org.slf4j.LoggerFactory.getLogger(SwarmKitRuntimeProvider::class.java) }
}
