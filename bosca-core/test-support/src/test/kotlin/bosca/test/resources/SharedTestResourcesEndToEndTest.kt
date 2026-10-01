package bosca.test.resources

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.sql.DriverManager
import java.time.Duration

class SharedTestResourcesEndToEndTest {
    @Test
    fun `postgres leases use one server and isolated databases`() {
        val first = SharedPostgreSQLContainer().apply { withDatabaseName("first test"); start() }
        val second = SharedPostgreSQLContainer().apply { withDatabaseName("second test"); start() }
        try {
            assertEquals(first.host, second.host)
            assertEquals(first.firstMappedPort, second.firstMappedPort)
            assertNotEquals(first.databaseName, second.databaseName)

            DriverManager.getConnection(first.jdbcUrl, first.username, first.password).use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE isolated_value (value text)") }
            }
            DriverManager.getConnection(second.jdbcUrl, second.username, second.password).use { connection ->
                connection.createStatement().use { statement ->
                    val result = statement.executeQuery(
                        "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'isolated_value')",
                    )
                    assertTrue(result.next())
                    assertFalse(result.getBoolean(1))
                }
            }
        } finally {
            second.stop()
            first.stop()
        }
    }

    @Test
    fun `nats leases use one server and isolated accounts`() {
        val first = SharedNatsContainer().apply { start() }
        val second = SharedNatsContainer().apply { start() }
        try {
            assertEquals(first.host, second.host)
            assertEquals(first.firstMappedPort, second.firstMappedPort)
            assertNotEquals(first.username, second.username)

            first.newConnection().use { firstConnection ->
                second.newConnection().use { secondConnection ->
                    val subscription = secondConnection.subscribe("shared.subject")
                    firstConnection.publish("shared.subject", "private".encodeToByteArray())
                    firstConnection.flush(Duration.ofSeconds(2))
                    assertEquals(null, subscription.nextMessage(Duration.ofMillis(250)))
                }
            }
        } finally {
            second.stop()
            first.stop()
        }
    }

    @Test
    fun `nats account is sanitized before it is leased again`() {
        SharedNatsContainer().apply {
            start()
            newConnection().use { connection ->
                connection.jetStreamManagement().addStream(
                    StreamConfiguration.builder()
                        .name("LEFTOVER")
                        .subjects("leftover.>")
                        .storageType(StorageType.Memory)
                        .build(),
                )
            }
            stop()
        }

        SharedNatsContainer().apply {
            start()
            try {
                newConnection().use { connection ->
                    assertTrue(connection.jetStreamManagement().streamNames.isEmpty())
                }
            } finally {
                stop()
            }
        }
    }

    @Test
    fun `valkey leases use one server with isolated keys and channels`(): Unit = runBlocking {
        val first = SharedValkeyContainer().apply { start() }
        val second = SharedValkeyContainer().apply { start() }
        try {
            assertEquals(first.host, second.host)
            assertEquals(first.firstMappedPort, second.firstMappedPort)
            assertNotEquals(first.database, second.database)
            assertNotEquals(first.namespace, second.namespace)

            val firstPool = first.newConnectionPool()
            val secondPool = second.newConnectionPool()
            val firstConnection = firstPool.connection()
            val secondConnection = secondPool.connection()
            try {
                firstConnection.sync().set("shared-key", "first")
                secondConnection.sync().set("shared-key", "second")
                assertEquals("first", firstConnection.sync().get("shared-key"))
                assertEquals("second", secondConnection.sync().get("shared-key"))

                val receivedByFirst = AtomicBoolean()
                val receivedBySecond = AtomicBoolean()
                val firstSubscription = firstPool.newPubSubConnection()
                val secondSubscription = secondPool.newPubSubConnection()
                try {
                    firstSubscription.addListener(object : RedisPubSubAdapter<String, String>() {
                        override fun message(channel: String, message: String) {
                            receivedByFirst.set(true)
                        }
                    })
                    secondSubscription.addListener(object : RedisPubSubAdapter<String, String>() {
                        override fun message(channel: String, message: String) {
                            receivedBySecond.set(true)
                        }
                    })
                    firstSubscription.sync().subscribe("shared-channel")
                    secondSubscription.sync().subscribe("shared-channel")
                    firstSubscription.sync().publish("shared-channel", "private")
                    delay(250)
                    assertTrue(receivedByFirst.get())
                    assertFalse(receivedBySecond.get())
                } finally {
                    secondSubscription.close()
                    firstSubscription.close()
                }
            } finally {
                firstPool.release(firstConnection)
                secondPool.release(secondConnection)
            }
        } finally {
            second.stop()
            first.stop()
        }
    }

    @Test
    fun `valkey account is sanitized before it is leased again`(): Unit = runBlocking {
        SharedValkeyContainer().apply {
            start()
            val pool = newConnectionPool()
            val connection = pool.connection()
            try {
                connection.sync().set("leftover", "value")
            } finally {
                pool.release(connection)
                stop()
            }
        }

        SharedValkeyContainer().apply {
            start()
            try {
                val pool = newConnectionPool()
                val connection = pool.connection()
                try {
                    assertNull(connection.sync().get("leftover"))
                } finally {
                    pool.release(connection)
                }
            } finally {
                stop()
            }
        }
    }

    @Test
    fun `meilisearch leases use one server with isolated index namespaces`() {
        val first = SharedMeilisearchContainer().apply { start() }
        val second = SharedMeilisearchContainer().apply { start() }
        try {
            assertEquals(first.host, second.host)
            assertEquals(first.firstMappedPort, second.firstMappedPort)
            assertNotEquals(first.namespace, second.namespace)

            val firstUid = createMeilisearchIndex(first, "shared")
            val secondUid = createMeilisearchIndex(second, "shared")
            assertNotEquals(firstUid, secondUid)

            first.stop()
            assertEquals(404, request(second.url, second.apiKey, "/indexes/$firstUid").GET().send().statusCode())
            assertEquals(200, request(second.url, second.apiKey, "/indexes/$secondUid").GET().send().statusCode())
        } finally {
            second.stop()
            first.stop()
        }
    }

    @Test
    fun `meilisearch indexes are sanitized when their lease closes`() {
        val container = SharedMeilisearchContainer().apply { start() }
        val url = container.url
        val apiKey = container.apiKey
        val uid = createMeilisearchIndex(container, "leftover")
        container.stop()

        assertEquals(404, request(url, apiKey, "/indexes/$uid").GET().send().statusCode())
    }

    private fun createMeilisearchIndex(container: SharedMeilisearchContainer, name: String): String {
        val uid = container.indexUid(name)
        val response = request(container.url, container.apiKey, "/indexes")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"uid":"$uid","primaryKey":"id"}"""))
            .send()
        assertEquals(202, response.statusCode())
        waitForMeilisearchTask(container.url, container.apiKey, response.taskUid())
        return uid
    }

    private fun waitForMeilisearchTask(url: String, apiKey: String, taskUid: String) {
        repeat(400) {
            val response = request(url, apiKey, "/tasks/$taskUid").GET().send()
            assertEquals(200, response.statusCode())
            when (Json.parseToJsonElement(response.body()).jsonObject
                .getValue("status").jsonPrimitive.content) {
                "succeeded" -> return
                "failed", "canceled" -> error("Meilisearch task failed: ${response.body()}")
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for Meilisearch task $taskUid")
    }

    private fun request(url: String, apiKey: String, path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("$url$path"))
            .header("Authorization", "Bearer $apiKey")

    private fun HttpRequest.Builder.send(): HttpResponse<String> =
        HttpClient.newHttpClient().send(build(), HttpResponse.BodyHandlers.ofString())

    private fun HttpResponse<String>.taskUid(): String =
        Json.parseToJsonElement(body()).jsonObject.getValue("taskUid").jsonPrimitive.content
}
