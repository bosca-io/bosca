package bosca.test.resources

import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Volume
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.lifecycle.Startable
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val POSTGRES_IMAGE = "pgvector/pgvector:pg18"
private const val POSTGRES_CONTAINER_NAME = "bosca-test-postgres-v2"
private const val POSTGRES_PORT = 5432
private const val POSTGRES_USER = "bosca_test"
private const val POSTGRES_PASSWORD = "bosca_test"
private const val POSTGRES_ADMIN_DATABASE = "postgres"

private const val NATS_IMAGE = "nats:2.12-alpine"
private const val NATS_CONTAINER_NAME = "bosca-test-nats-v1"
private const val NATS_PORT = 4222
private const val NATS_ACCOUNT_COUNT = 128
private const val NATS_PASSWORD = "bosca_test"

private const val VALKEY_IMAGE = "valkey/valkey:8-alpine"
private const val VALKEY_CONTAINER_NAME = "bosca-test-valkey-v1"
private const val VALKEY_PORT = 6379
private const val VALKEY_ACCOUNT_COUNT = 128

private const val MEILISEARCH_IMAGE = "getmeili/meilisearch:v1.42.0"
private const val MEILISEARCH_CONTAINER_NAME = "bosca-test-meilisearch-v1"
private const val MEILISEARCH_PORT = 7700
private const val MEILISEARCH_MASTER_KEY = "test-master-key"
private const val MEILISEARCH_NAMESPACE_COUNT = 128

private val resourceRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "bosca-test-resources-v1")

/**
 * Controls the process-independent PostgreSQL, NATS, Valkey, and Meilisearch services used by tests.
 *
 * Tests normally start these services lazily when taking their first lease. CI can call [start]
 * before the test graph to move container startup out of individual test execution, then call
 * [stop] after the graph completes to release the agent's container resources.
 */
object SharedTestResources {
    /** Starts all shared services and waits until they accept connections. */
    fun start() = SharedTestResourceManager.start()

    /** Stops all shared services without removing their reusable container definitions. */
    fun stop() = SharedTestResourceManager.stop()
}

/** Command-line entry point used by the workspace lifecycle tasks. */
fun main(args: Array<String>) {
    when (args.singleOrNull()) {
        "up" -> {
            SharedTestResources.start()
            println("Shared PostgreSQL, NATS, Valkey, and Meilisearch test resources are ready.")
        }
        "down" -> {
            SharedTestResources.stop()
            println("Shared PostgreSQL, NATS, Valkey, and Meilisearch test resources are stopped.")
        }
        else -> throw IllegalArgumentException("Expected exactly one command: up or down")
    }
}

/**
 * A PostgreSQL-compatible test fixture backed by the workspace-wide shared pgvector server.
 *
 * Starting the fixture leases a new database; stopping it drops only that database. The shared
 * server is deliberately not coupled to the fixture or test JVM lifecycle.
 */
class SharedPostgreSQLContainer(
    @Suppress("UNUSED_PARAMETER") imageName: String = POSTGRES_IMAGE,
) : Startable {
    private var requestedDatabaseName = "test"
    private var lease: PostgresDatabaseLease? = null

    /** Sets the human-readable prefix used for the unique leased database name. */
    fun withDatabaseName(databaseName: String): SharedPostgreSQLContainer = apply {
        check(lease == null) { "Database name cannot be changed after the lease starts" }
        requestedDatabaseName = databaseName
    }

    /** Retained for source compatibility; shared test databases always use managed credentials. */
    fun withUsername(@Suppress("UNUSED_PARAMETER") username: String): SharedPostgreSQLContainer = this

    /** Retained for source compatibility; shared test databases always use managed credentials. */
    fun withPassword(@Suppress("UNUSED_PARAMETER") password: String): SharedPostgreSQLContainer = this

    /** Retained for source compatibility; the manager exposes the standard PostgreSQL port. */
    fun withExposedPorts(@Suppress("UNUSED_PARAMETER") vararg ports: Int): SharedPostgreSQLContainer = this

    /** Retained for source compatibility; PostgreSQL credentials and database names are lease-managed. */
    fun withEnv(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: String,
    ): SharedPostgreSQLContainer = this

    /** Retained for source compatibility; readiness is enforced centrally by the manager. */
    fun waitingFor(@Suppress("UNUSED_PARAMETER") strategy: WaitStrategy): SharedPostgreSQLContainer = this

    /** Retained for source compatibility; the backing service is always reusable and out of band. */
    fun withReuse(@Suppress("UNUSED_PARAMETER") reuse: Boolean): SharedPostgreSQLContainer = this

    override fun start() {
        if (lease == null) {
            lease = SharedTestResourceManager.leasePostgresDatabase(requestedDatabaseName)
        }
    }

    override fun stop() {
        lease?.close()
        lease = null
    }

    /** JDBC URL for this fixture's isolated database. */
    val jdbcUrl: String get() = requireLease().jdbcUrl

    /** Managed PostgreSQL username. */
    val username: String get() = POSTGRES_USER

    /** Managed PostgreSQL password. */
    val password: String get() = POSTGRES_PASSWORD

    /** Unique database name allocated to this fixture. */
    val databaseName: String get() = requireLease().databaseName

    /** Docker host address for compatibility with existing Testcontainers fixtures. */
    val host: String get() = requireLease().endpoint.host

    /** Dynamically mapped PostgreSQL port. */
    val firstMappedPort: Int get() = requireLease().endpoint.port

    /** Returns the mapped PostgreSQL port. */
    fun getMappedPort(originalPort: Int): Int {
        require(originalPort == POSTGRES_PORT) { "Only PostgreSQL port $POSTGRES_PORT is exposed" }
        return firstMappedPort
    }

    /** Whether this fixture currently holds a database lease. */
    val isRunning: Boolean get() = lease != null

    private fun requireLease(): PostgresDatabaseLease =
        checkNotNull(lease) { "SharedPostgreSQLContainer.start() must be called first" }
}

/**
 * A NATS test fixture backed by the workspace-wide shared server.
 *
 * Each fixture holds an exclusive account lease. NATS subjects, streams, consumers, and key-value
 * buckets are isolated by the server account rather than by starting another server process.
 */
class SharedNatsContainer(
    @Suppress("UNUSED_PARAMETER") imageName: String = NATS_IMAGE,
) : Startable {
    private var lease: NatsAccountLease? = null
    private val connections = CopyOnWriteArrayList<Connection>()
    private val connectionPools = CopyOnWriteArrayList<NatsConnectionPool>()

    /** Retained for source compatibility; the shared server exposes only its NATS client port. */
    fun withExposedPorts(@Suppress("UNUSED_PARAMETER") vararg ports: Int): SharedNatsContainer = this

    /** Retained for source compatibility; JetStream is always enabled by the manager. */
    fun withCommand(@Suppress("UNUSED_PARAMETER") vararg command: String): SharedNatsContainer = this

    /** Retained for source compatibility; the backing service is always reusable and out of band. */
    fun withReuse(@Suppress("UNUSED_PARAMETER") reuse: Boolean): SharedNatsContainer = this

    /** Retained for source compatibility; readiness is enforced centrally by the manager. */
    fun waitingFor(@Suppress("UNUSED_PARAMETER") strategy: WaitStrategy): SharedNatsContainer = this

    override fun start() {
        if (lease == null) {
            lease = SharedTestResourceManager.leaseNatsAccount()
        }
    }

    override fun stop() {
        kotlinx.coroutines.runBlocking {
            connectionPools.forEach { it.close() }
        }
        connectionPools.clear()
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        lease?.close()
        lease = null
    }

    /** Authenticated NATS URL scoped to this fixture's account. */
    val url: String get() = requireLease().url

    /** Username associated with this fixture's isolated NATS account. */
    val username: String get() = requireLease().username

    /** Password associated with this fixture's isolated NATS account. */
    val password: String get() = NATS_PASSWORD

    /** Docker host address for compatibility with existing Testcontainers fixtures. */
    val host: String get() = requireLease().endpoint.host

    /** Dynamically mapped NATS client port. */
    val firstMappedPort: Int get() = requireLease().endpoint.port

    /** Returns the mapped NATS client port. */
    fun getMappedPort(originalPort: Int): Int {
        require(originalPort == NATS_PORT) { "Only NATS port $NATS_PORT is exposed" }
        return firstMappedPort
    }

    /** Opens a direct authenticated connection in this fixture's account. */
    fun newConnection(): Connection = Nats.connect(requireLease().options()).also(connections::add)

    /** Creates a Bosca connection pool authenticated to this fixture's account. */
    fun newConnectionPool(maxConnections: Int = 50): NatsConnectionPool =
        NatsConnectionPool(url, username, password, maxConnections).also(connectionPools::add)

    /** Whether this fixture currently holds an account lease. */
    val isRunning: Boolean get() = lease != null

    private fun requireLease(): NatsAccountLease =
        checkNotNull(lease) { "SharedNatsContainer.start() must be called first" }
}

/**
 * A Valkey test fixture backed by the workspace-wide shared server.
 *
 * Each fixture leases a logical database for key isolation and a Pub/Sub channel namespace because
 * Redis-compatible Pub/Sub otherwise crosses logical database boundaries.
 */
class SharedValkeyContainer(
    @Suppress("UNUSED_PARAMETER") imageName: String = VALKEY_IMAGE,
) : Startable {
    private var lease: ValkeyAccountLease? = null
    private val connectionPools = CopyOnWriteArrayList<RedisConnectionPool>()

    /** Retained for source compatibility; the shared server exposes only its client port. */
    fun withExposedPorts(@Suppress("UNUSED_PARAMETER") vararg ports: Int): SharedValkeyContainer = this

    /** Retained for source compatibility; server configuration is managed centrally. */
    fun withCommand(@Suppress("UNUSED_PARAMETER") vararg command: String): SharedValkeyContainer = this

    /** Retained for source compatibility; the backing service is always reusable and out of band. */
    fun withReuse(@Suppress("UNUSED_PARAMETER") reuse: Boolean): SharedValkeyContainer = this

    /** Retained for source compatibility; readiness is enforced centrally by the manager. */
    fun waitingFor(@Suppress("UNUSED_PARAMETER") strategy: WaitStrategy): SharedValkeyContainer = this

    override fun start() {
        if (lease == null) {
            lease = SharedTestResourceManager.leaseValkeyAccount()
        }
    }

    override fun stop() {
        kotlinx.coroutines.runBlocking {
            connectionPools.forEach { it.close() }
        }
        connectionPools.clear()
        lease?.close()
        lease = null
    }

    /** Docker host address for compatibility with existing Testcontainers fixtures. */
    val host: String get() = requireLease().endpoint.host

    /** Dynamically mapped Valkey client port. */
    val firstMappedPort: Int get() = requireLease().endpoint.port

    /** Logical database allocated to this fixture. */
    val database: Int get() = requireLease().accountIndex

    /** Protocol namespace allocated to this fixture's Pub/Sub channels. */
    val namespace: String get() = requireLease().namespace

    /** Returns the mapped Valkey client port. */
    fun getMappedPort(originalPort: Int): Int {
        require(originalPort == VALKEY_PORT) { "Only Valkey port $VALKEY_PORT is exposed" }
        return firstMappedPort
    }

    /** Creates a Bosca connection pool isolated to this fixture's database and namespace. */
    fun newConnectionPool(maxConnections: Int = 50): RedisConnectionPool {
        val currentLease = requireLease()
        return RedisConnectionPool(
            currentLease.endpoint.host,
            currentLease.endpoint.port,
            currentLease.accountIndex,
            currentLease.namespace,
            maxConnections,
        ).also(connectionPools::add)
    }

    /** Whether this fixture currently holds an account lease. */
    val isRunning: Boolean get() = lease != null

    private fun requireLease(): ValkeyAccountLease =
        checkNotNull(lease) { "SharedValkeyContainer.start() must be called first" }
}

/**
 * A Meilisearch test fixture backed by the workspace-wide shared server.
 *
 * Each fixture leases an index UID namespace. Tests can use [indexUid] to map their ordinary index
 * names into that namespace, allowing independent indexes to share the warm server concurrently.
 * Only indexes belonging to the fixture are deleted before and after its lease.
 */
class SharedMeilisearchContainer(
    @Suppress("UNUSED_PARAMETER") imageName: String = MEILISEARCH_IMAGE,
) : Startable {
    private var lease: MeilisearchLease? = null

    /** Retained for source compatibility; the shared server exposes only its HTTP port. */
    fun withExposedPorts(@Suppress("UNUSED_PARAMETER") vararg ports: Int): SharedMeilisearchContainer = this

    /** Retained for source compatibility; credentials and analytics settings are managed centrally. */
    fun withEnv(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: String,
    ): SharedMeilisearchContainer = this

    /** Retained for source compatibility; the backing service is always reusable and out of band. */
    fun withReuse(@Suppress("UNUSED_PARAMETER") reuse: Boolean): SharedMeilisearchContainer = this

    /** Retained for source compatibility; readiness is enforced centrally by the manager. */
    fun waitingFor(@Suppress("UNUSED_PARAMETER") strategy: WaitStrategy): SharedMeilisearchContainer = this

    override fun start() {
        if (lease == null) {
            lease = SharedTestResourceManager.leaseMeilisearch()
        }
    }

    override fun stop() {
        lease?.close()
        lease = null
    }

    /** Base URL for the shared Meilisearch HTTP API. */
    val url: String get() = requireLease().url

    /** Master API key configured on the shared test server. */
    val apiKey: String get() = MEILISEARCH_MASTER_KEY

    /** Index namespace allocated to this fixture. */
    val namespace: String get() = requireLease().namespace

    /** Maps a test-local index name to a UID isolated within this fixture's namespace. */
    fun indexUid(name: String): String = requireLease().indexUid(name)

    /** Docker host address for compatibility with existing Testcontainers fixtures. */
    val host: String get() = requireLease().endpoint.host

    /** Dynamically mapped Meilisearch HTTP port. */
    val firstMappedPort: Int get() = requireLease().endpoint.port

    /** Returns the mapped Meilisearch HTTP port. */
    fun getMappedPort(originalPort: Int): Int {
        require(originalPort == MEILISEARCH_PORT) { "Only Meilisearch port $MEILISEARCH_PORT is exposed" }
        return firstMappedPort
    }

    /** Whether this fixture currently holds an index namespace lease. */
    val isRunning: Boolean get() = lease != null

    private fun requireLease(): MeilisearchLease =
        checkNotNull(lease) { "SharedMeilisearchContainer.start() must be called first" }
}

private object SharedTestResourceManager {
    private val lifecycleLockPath = resourceRoot.resolve("lifecycle.lock")
    private val lifecycleJvmLock = ReentrantLock()

    fun start() {
        withLifecycleLock {
            ensurePostgres()
            ensureNats()
            ensureValkey()
            ensureMeilisearch()
        }
    }

    fun stop() {
        withLifecycleLock {
            val client = DockerClientFactory.instance().client()
            stopContainer(client, MEILISEARCH_CONTAINER_NAME)
            stopContainer(client, VALKEY_CONTAINER_NAME)
            stopContainer(client, NATS_CONTAINER_NAME)
            stopContainer(client, POSTGRES_CONTAINER_NAME)
        }
    }

    fun leasePostgresDatabase(prefix: String): PostgresDatabaseLease {
        val endpoint = withLifecycleLock { ensurePostgres() }
        val databaseName = uniqueDatabaseName(prefix)
        val adminUrl = "jdbc:postgresql://${endpoint.host}:${endpoint.port}/$POSTGRES_ADMIN_DATABASE?sslmode=disable"
        Class.forName("org.postgresql.Driver")
        DriverManager.getConnection(adminUrl, POSTGRES_USER, POSTGRES_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE ${quotedIdentifier(databaseName)}")
            }
        }
        return PostgresDatabaseLease(endpoint, databaseName, adminUrl)
    }

    fun leaseNatsAccount(): NatsAccountLease {
        val endpoint = withLifecycleLock { ensureNats() }
        Files.createDirectories(resourceRoot.resolve("nats-accounts"))
        repeat(NATS_ACCOUNT_COUNT) { index ->
            val channel = FileChannel.open(
                resourceRoot.resolve("nats-accounts/account-$index.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }
            if (lock != null) {
                val lease = NatsAccountLease(endpoint, index, channel, lock)
                try {
                    lease.clear()
                    return lease
                } catch (error: Throwable) {
                    lease.releaseLock()
                    throw error
                }
            }
            channel.close()
        }
        error("All $NATS_ACCOUNT_COUNT shared NATS test accounts are currently leased")
    }

    fun leaseValkeyAccount(): ValkeyAccountLease {
        val endpoint = withLifecycleLock { ensureValkey() }
        val accountRoot = resourceRoot.resolve("valkey-accounts")
        Files.createDirectories(accountRoot)
        repeat(VALKEY_ACCOUNT_COUNT) { index ->
            val channel = FileChannel.open(
                accountRoot.resolve("account-$index.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }
            if (lock != null) {
                val lease = ValkeyAccountLease(endpoint, index, channel, lock)
                try {
                    lease.clear()
                    return lease
                } catch (error: Throwable) {
                    lease.releaseLock()
                    throw error
                }
            }
            channel.close()
        }
        error("All $VALKEY_ACCOUNT_COUNT shared Valkey test accounts are currently leased")
    }

    fun leaseMeilisearch(): MeilisearchLease {
        val endpoint = withLifecycleLock { ensureMeilisearch() }
        val namespaceRoot = resourceRoot.resolve("meilisearch-namespaces")
        Files.createDirectories(namespaceRoot)
        repeat(MEILISEARCH_NAMESPACE_COUNT) { index ->
            val channel = FileChannel.open(
                namespaceRoot.resolve("namespace-$index.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            }
            if (lock != null) {
                val lease = MeilisearchLease(endpoint, index, channel, lock)
                try {
                    lease.clear()
                    return lease
                } catch (error: Throwable) {
                    lease.releaseLock()
                    throw error
                }
            }
            channel.close()
        }
        error("All $MEILISEARCH_NAMESPACE_COUNT shared Meilisearch test namespaces are currently leased")
    }

    private fun ensurePostgres(): ServiceEndpoint = ensureContainer(
        containerName = POSTGRES_CONTAINER_NAME,
        image = POSTGRES_IMAGE,
        privatePort = POSTGRES_PORT,
    ) { command ->
        command
            .withEnv(
                "POSTGRES_USER=$POSTGRES_USER",
                "POSTGRES_PASSWORD=$POSTGRES_PASSWORD",
                "POSTGRES_DB=$POSTGRES_ADMIN_DATABASE",
            )
            .withCmd(
                "postgres",
                "-c", "max_connections=1000",
                "-c", "fsync=off",
                "-c", "synchronous_commit=off",
                "-c", "full_page_writes=off",
            )
            .withHostConfig(
                sharedHostConfig(POSTGRES_PORT).withTmpFs(
                    mapOf("/var/lib/postgresql" to "rw,noexec,nosuid,size=4g"),
                ),
            )
    }.also { endpoint ->
        waitFor("PostgreSQL", endpoint) {
            DriverManager.getConnection(
                "jdbc:postgresql://${endpoint.host}:${endpoint.port}/$POSTGRES_ADMIN_DATABASE?sslmode=disable",
                POSTGRES_USER,
                POSTGRES_PASSWORD,
            ).use { true }
        }
    }

    private fun ensureNats(): ServiceEndpoint {
        val config = writeNatsConfig()
        return ensureContainer(
            containerName = NATS_CONTAINER_NAME,
            image = NATS_IMAGE,
            privatePort = NATS_PORT,
        ) { command ->
            command
                .withCmd("-c", "/etc/nats/nats-server.conf")
                .withEnv("GOMEMLIMIT=4GiB")
                .withHostConfig(
                    sharedHostConfig(NATS_PORT)
                        .withBinds(Bind(config.toString(), Volume("/etc/nats/nats-server.conf"), AccessMode.ro))
                        .withTmpFs(mapOf("/data/jetstream" to "rw,noexec,nosuid,size=4g")),
                )
        }.also { endpoint ->
            waitFor("NATS", endpoint) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 500)
                }
                true
            }
        }
    }

    private fun ensureValkey(): ServiceEndpoint = ensureContainer(
        containerName = VALKEY_CONTAINER_NAME,
        image = VALKEY_IMAGE,
        privatePort = VALKEY_PORT,
    ) { command ->
        command
            .withCmd(
                "valkey-server",
                "--save", "",
                "--appendonly", "no",
                "--databases", VALKEY_ACCOUNT_COUNT.toString(),
            )
            .withHostConfig(
                sharedHostConfig(VALKEY_PORT).withTmpFs(mapOf("/data" to "rw,noexec,nosuid,size=2g")),
            )
    }.also { endpoint ->
        waitFor("Valkey", endpoint) {
            val client = RedisClient.create(RedisURI.create(endpoint.host, endpoint.port))
            try {
                client.connect().use { it.sync().ping() == "PONG" }
            } finally {
                client.shutdown()
            }
        }
    }

    private fun ensureMeilisearch(): ServiceEndpoint = ensureContainer(
        containerName = MEILISEARCH_CONTAINER_NAME,
        image = MEILISEARCH_IMAGE,
        privatePort = MEILISEARCH_PORT,
    ) { command ->
        command
            .withEnv(
                "MEILI_MASTER_KEY=$MEILISEARCH_MASTER_KEY",
                "MEILI_NO_ANALYTICS=true",
                "MEILI_ENV=development",
            )
            .withHostConfig(
                sharedHostConfig(MEILISEARCH_PORT).withTmpFs(
                    mapOf("/meili_data" to "rw,noexec,nosuid,size=2g"),
                ),
            )
    }.also { endpoint ->
        waitFor("Meilisearch", endpoint) {
            val response = meilisearchRequest(endpoint, "/health").GET().send()
            response.statusCode() == 200
        }
    }

    private fun ensureContainer(
        containerName: String,
        image: String,
        privatePort: Int,
        configure: (com.github.dockerjava.api.command.CreateContainerCmd) -> com.github.dockerjava.api.command.CreateContainerCmd,
    ): ServiceEndpoint {
        val factory = DockerClientFactory.instance()
        val client = factory.client()
        var container = findContainer(client, containerName)
        if (container == null) {
            factory.checkAndPullImage(client, image)
            val exposedPort = ExposedPort.tcp(privatePort)
            val command = client.createContainerCmd(image)
                .withName(containerName)
                .withExposedPorts(exposedPort)
                .withLabels(
                    mapOf(
                        "io.bosca.test-resource" to "true",
                        "io.bosca.test-resource.version" to "1",
                    ),
                )
            configure(command).exec()
            container = checkNotNull(findContainer(client, containerName))
        }
        if (container.state != "running") {
            client.startContainerCmd(container.id).exec()
            container = checkNotNull(findContainer(client, containerName))
        }
        val publicPort = container.ports
            .firstOrNull { it.privatePort == privatePort && it.type == "tcp" }
            ?.publicPort
        return ServiceEndpoint(factory.dockerHostIpAddress(), checkNotNull(publicPort))
    }

    private fun findContainer(client: DockerClient, name: String): Container? =
        client.listContainersCmd()
            .withShowAll(true)
            .withNameFilter(listOf(name))
            .exec()
            .firstOrNull { container -> container.names.any { it == "/$name" } }

    private fun stopContainer(client: DockerClient, name: String) {
        val container = findContainer(client, name) ?: return
        if (container.state == "running") {
            client.stopContainerCmd(container.id).withTimeout(30).exec()
        }
    }

    private fun sharedHostConfig(port: Int): HostConfig = HostConfig.newHostConfig()
        .withPortBindings(PortBinding(Ports.Binding.empty(), ExposedPort.tcp(port)))
        .withRestartPolicy(RestartPolicy.unlessStoppedRestart())

    private fun writeNatsConfig(): Path {
        Files.createDirectories(resourceRoot)
        val path = resourceRoot.resolve("nats-server-v1.conf")
        val accounts = buildString {
            repeat(NATS_ACCOUNT_COUNT) { index ->
                val username = natsUsername(index)
                appendLine("  TEST_$index: {")
                appendLine("    jetstream: { max_memory: 32MB, max_file: 32MB, max_streams: 256, max_consumers: 1024 }")
                appendLine("    users: [{ user: \"$username\", password: \"$NATS_PASSWORD\" }]")
                appendLine("  },")
            }
            appendLine("  SYS: { users: [{ user: \"bosca_test_system\", password: \"$NATS_PASSWORD\" }] }")
        }
        Files.writeString(
            path,
            """
            port: $NATS_PORT
            server_name: bosca-test-resources
            system_account: SYS
            jetstream: {
              store_dir: "/data/jetstream"
              max_memory_store: 4GB
              max_file_store: 4GB
            }
            accounts: {
            $accounts}
            """.trimIndent(),
        )
        return path
    }

    private fun <T> withLifecycleLock(block: () -> T): T {
        return lifecycleJvmLock.withLock {
            Files.createDirectories(resourceRoot)
            FileChannel.open(
                lifecycleLockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun waitFor(service: String, endpoint: ServiceEndpoint, probe: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                if (probe()) return
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("$service did not become ready at ${endpoint.host}:${endpoint.port}", lastError)
    }

}

private data class ServiceEndpoint(val host: String, val port: Int)

private class PostgresDatabaseLease(
    val endpoint: ServiceEndpoint,
    val databaseName: String,
    private val adminUrl: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val jdbcUrl: String = "jdbc:postgresql://${endpoint.host}:${endpoint.port}/$databaseName?sslmode=disable"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        DriverManager.getConnection(adminUrl, POSTGRES_USER, POSTGRES_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE datname = '${databaseName.replace("'", "''")}' AND pid <> pg_backend_pid()",
                )
                statement.execute("DROP DATABASE IF EXISTS ${quotedIdentifier(databaseName)}")
            }
        }
    }
}

private class NatsAccountLease(
    val endpoint: ServiceEndpoint,
    private val accountIndex: Int,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val username: String = natsUsername(accountIndex)
    val url: String = "nats://$username:$NATS_PASSWORD@${endpoint.host}:${endpoint.port}"

    fun options(): Options = Options.Builder()
        .server("nats://${endpoint.host}:${endpoint.port}")
        .userInfo(username, NATS_PASSWORD)
        .build()

    fun clear() {
        Nats.connect(options()).use { connection ->
            val management = connection.jetStreamManagement()
            management.streamNames.forEach { management.deleteStream(it) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            clear()
        } finally {
            releaseLock()
        }
    }

    fun releaseLock() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}

private class ValkeyAccountLease(
    val endpoint: ServiceEndpoint,
    val accountIndex: Int,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val namespace: String = "bosca_test_${accountIndex.toString().padStart(3, '0')}"

    fun clear() {
        val client = RedisClient.create(
            RedisURI.Builder.redis(endpoint.host, endpoint.port).withDatabase(accountIndex).build(),
        )
        try {
            client.connect().use { it.sync().flushdb() }
        } finally {
            client.shutdown()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            clear()
        } finally {
            releaseLock()
        }
    }

    fun releaseLock() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}

private class MeilisearchLease(
    val endpoint: ServiceEndpoint,
    namespaceIndex: Int,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val url: String = "http://${endpoint.host}:${endpoint.port}"
    val namespace: String = "bosca_test_${namespaceIndex.toString().padStart(3, '0')}"

    fun indexUid(name: String): String {
        val normalized = name
            .replace(Regex("[^a-zA-Z0-9_-]+"), "_")
            .trim('_')
        require(normalized.isNotEmpty()) { "Meilisearch test index name must contain an alphanumeric character" }
        return "${namespace}_$normalized"
    }

    fun clear() {
        val response = meilisearchRequest(endpoint, "/indexes?limit=1000").GET().send()
        check(response.statusCode() == 200) {
            "Failed to list shared Meilisearch indexes: HTTP ${response.statusCode()} ${response.body()}"
        }
        val indexes = Json.parseToJsonElement(response.body()).jsonObject["results"]?.jsonArray.orEmpty()
        indexes.filter { index ->
            index.jsonObject.getValue("uid").jsonPrimitive.content.startsWith("${namespace}_")
        }.forEach { index ->
            val uid = index.jsonObject.getValue("uid").jsonPrimitive.content
            val encodedUid = URLEncoder.encode(uid, Charsets.UTF_8).replace("+", "%20")
            val deleteResponse = meilisearchRequest(endpoint, "/indexes/$encodedUid").DELETE().send()
            check(deleteResponse.statusCode() == 202) {
                "Failed to delete shared Meilisearch index $uid: " +
                    "HTTP ${deleteResponse.statusCode()} ${deleteResponse.body()}"
            }
            val taskUid = Json.parseToJsonElement(deleteResponse.body())
                .jsonObject.getValue("taskUid").jsonPrimitive.content
            waitForTask(taskUid)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            clear()
        } finally {
            releaseLock()
        }
    }

    fun releaseLock() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    private fun waitForTask(taskUid: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            val response = meilisearchRequest(endpoint, "/tasks/$taskUid").GET().send()
            check(response.statusCode() == 200) {
                "Failed to inspect shared Meilisearch task $taskUid: HTTP ${response.statusCode()} ${response.body()}"
            }
            when (val status = Json.parseToJsonElement(response.body())
                .jsonObject.getValue("status").jsonPrimitive.content) {
                "succeeded" -> return
                "failed", "canceled" -> error("Shared Meilisearch cleanup task $taskUid $status: ${response.body()}")
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for shared Meilisearch cleanup task $taskUid")
    }
}

private fun meilisearchRequest(endpoint: ServiceEndpoint, path: String): HttpRequest.Builder =
    HttpRequest.newBuilder(URI("http://${endpoint.host}:${endpoint.port}$path"))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $MEILISEARCH_MASTER_KEY")

private fun HttpRequest.Builder.send(): HttpResponse<String> =
    HttpClient.newHttpClient().send(build(), HttpResponse.BodyHandlers.ofString())

private fun uniqueDatabaseName(prefix: String): String {
    val normalized = prefix.lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifEmpty { "test" }
        .take(36)
    return "${normalized}_${UUID.randomUUID().toString().replace("-", "").take(20)}"
}

private fun quotedIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun natsUsername(index: Int): String = "bosca_test_${index.toString().padStart(3, '0')}"
