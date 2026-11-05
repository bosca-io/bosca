@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.kit.harness.runtime

import bosca.ai.kit.configuration.KitMigration
import bosca.ai.kit.harness.*
import bosca.db.*
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real PostgreSQL mapping and transactions with the controller HTTP boundary. */
class SwarmKitRuntimeProviderIntegrationTest {
    @Test fun `committed dispatch survives replacement and cancellation removes encrypted intent only after confirmation`(): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        val responseCode = AtomicInteger(200)
        val stopped = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val configText = """kit: {swarm: {controller: {url: "http://127.0.0.1:${server.address.port}", token: "isolated-controller-token"}}}"""
        val application = BoscaApplication(ApplicationConfig.load(configText.byteInputStream()), ownsServices = false)
        val json = application.json
        val observation = KitSwarmExecution(OffsetDateTime.now().toString(), false, "http://runtime.invalid:8080", "runtime@sha256:" + "a".repeat(64), "sha256:" + "a".repeat(64), "task-id")
        server.createContext("/v1/runtimes/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            calls += exchange.requestMethod to body
            val code = if (exchange.requestHeaders.getFirst("Authorization") == "Bearer isolated-controller-token") responseCode.get() else 401
            val response = when {
                code != 200 -> "Controller fixture failure"
                exchange.requestURI.path.endsWith("/stop") -> (stopped.get() == 1).toString()
                else -> json.encodeToString(KitSwarmExecution.serializer(), observation)
            }.toByteArray()
            exchange.sendResponseHeaders(code, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4), key = "kit-swarm-provider"))
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        val repository = KitSwarmRepositoryImpl()
        val sessions = KitRuntimeRepositoryImpl()
        val session = KitRuntimeSession(UUID.random(), UUID.random(), JsonObject(emptyMap()), JsonObject(emptyMap()), idleAt = OffsetDateTime.now())
        val configuration = KitHarnessConfiguration("swarm", "http://{service}:8080", "http://bosca.invalid/graphql", scopes = listOf("content:view"), image = observation.image.orEmpty(), imageId = observation.imageId.orEmpty(),
            versions = RuntimeVersions("1", "2", "3", "4"), infrastructure = KitInfrastructure.SWARM)
        val secrets = KitRuntimeSecrets(session.sessionId, "encrypted-nonce", "encrypted-ciphertext", listOf("BOSCA_TOKEN", "BOSCA_RUNTIME_TOKEN", "BOSCA_RUNTIME_EXPIRES"))
        val environment = mapOf("BOSCA_CHAT_SESSION_ID" to session.sessionId.toString(), "BOSCA_RUNTIME_GENERATION" to "1")
        val key = "kit:${session.sessionId}:1"
        fun provider() = SwarmKitRuntimeProvider(repository, application, json)
        try {
            FlywayMigration(pool).migrate(listOf(KitMigration()))
            withConnectionManager {
                sessions.create(session)
                var rolledBack: UUID? = null
                assertFailsWith<IllegalStateException> { transaction {
                    rolledBack = provider().ensure(configuration, key, environment, secrets)
                    error("Injected admission rollback")
                } }
                assertNull(repository.get(assertNotNull(rolledBack)))
                assertTrue(calls.isEmpty(), "Uncommitted admission must not contact the manager")
                val reference = transaction { provider().ensure(configuration, key, environment, secrets) }
                assertTrue(calls.isEmpty(), "Dispatch is materialized by recovery after commit")
                val intent = assertNotNull(repository.get(reference))
                assertEquals(session.sessionId, intent.sessionId)
                assertEquals(secrets, intent.request?.secrets, "Generated JSONB mapper retains typed encrypted payload")
                assertEquals(reference, provider().ensure(configuration, key, environment, secrets))
                assertFailsWith<IllegalStateException> { provider().ensure(configuration, key, environment + ("changed" to "value"), secrets) }
                val execution = assertNotNull(provider().status(configuration, reference))
                assertEquals(observation.endpoint, execution.endpoint)
                assertEquals(intent.created, execution.created, "Activation budget starts at committed admission, independent of manager creation time")
                assertTrue(repository.get(reference)?.materialized == true)
                assertEquals(intent.request, json.decodeFromString(KitSwarmRequest.serializer(), calls.single().second))
                responseCode.set(503)
                assertFalse(provider().isStopped(configuration, reference), "Manager outage cannot prove a healthy task has stopped")
                assertFailsWith<KitSwarmControllerException> { provider().status(configuration, reference) }
                responseCode.set(422)
                assertTrue(provider().status(configuration, reference)?.terminal == true, "Rejected profiles fail activation rather than retry indefinitely")
                responseCode.set(200)
                val beforeStop = calls.size
                assertFailsWith<IllegalStateException> { transaction { provider().stop(configuration, reference); error("Injected cancellation rollback") } }
                assertFalse(assertNotNull(repository.get(reference)).stopping)
                transaction { provider().stop(configuration, reference) }
                assertEquals(beforeStop, calls.size, "Cancellation intent commits before contacting the manager")
                assertFalse(provider().isStopped(configuration, reference))
                assertNotNull(repository.get(reference)?.request)
                responseCode.set(503)
                assertFailsWith<KitSwarmControllerException> { provider().isStopped(configuration, reference) }
                assertNotNull(repository.get(reference)?.request, "Failed removal remains retryable with its encrypted request")
                responseCode.set(200); stopped.set(1)
                assertTrue(provider().isStopped(configuration, reference))
                val removed = assertNotNull(repository.get(reference))
                assertTrue(removed.stopped); assertNull(removed.request)
                val afterStop = calls.size
                assertTrue(provider().isStopped(configuration, reference))
                assertEquals(afterStop, calls.size, "Confirmed removal survives provider replacement without another RPC")
                assertFailsWith<IllegalStateException> { provider().ensure(configuration, key, environment, secrets) }
                val expired = KitSwarmDispatch(UUID.random(), session.sessionId, "swarm", "expired-$key", intent.request, created = OffsetDateTime.now().minusHours(1))
                repository.create(expired)
                assertTrue(provider().status(configuration, expired.id)?.terminal == true)
                assertEquals(afterStop, calls.size, "Expired startup cannot allocate a late task")
                val owner = UUID.random()
                assertEquals(1, sessions.save(session.copy(owner = owner, deletedAt = OffsetDateTime.now())))
                sessions.deleteSession(session.sessionId, owner, session.epoch)
                assertNull(repository.get(reference)); assertNull(repository.get(expired.id), "Session purge removes all generation dispatches")
            }
        } finally {
            server.stop(0)
            ProviderRegistry.clear(); pool.close(); postgres.stop()
        }
    }
}
