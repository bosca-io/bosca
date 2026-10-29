package bosca.backup.graphql

import bosca.backup.BackupJobQueueNames
import bosca.backup.BackupObject
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupProgressService
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.backup.configuration.BackupSchemaRegistrar
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.BackupTestProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.graphql.GraphQLRequest
import bosca.graphql.SchemaRegistrar
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.observability.ErrorCapture
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared real GraphQL, authorization and PostgreSQL fixture for object run administration. */
@OptIn(InternalDI::class)
abstract class ObjectBackupGraphQLTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    protected lateinit var runs: ObjectBackupRunService
    protected lateinit var progress: ObjectBackupProgressService
    private lateinit var graphql: ObjectBackupQueryTestService
    protected lateinit var directory: Path
    private lateinit var application: BoscaApplication
    protected lateinit var source: ManagedFileSystemObjectStorageService
    protected lateinit var destination: ManagedFileSystemObjectStorageService
    protected lateinit var run: ObjectBackupRun
    protected lateinit var json: Json
    protected val first = entry("items/a", "data")
    protected val second = entry("items/b", "more")
    protected val inventory = ObjectBackupManifest(
        id = "inventory-1", sourceId = "source-1", scope = "content",
        created = "2026-10-16T00:00:00Z", objects = listOf(first, second),
    )
    private val adminGroup = Group(name = "administrators", description = "", type = GroupType.SYSTEM)
    protected val admin = ImpersonatedAuthenticationContext(Principal(), listOf(adminGroup))
    protected val anonymous = AuthenticationContext(null, null)

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_graphql").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-graphql-test",
        ))
        ProviderRegistry.clear()
        DispatchersRegistry.clear()
        directory = createTempDirectory("bosca-object-graphql-")
        application = BoscaApplication(applicationConfig(), ownsServices = false)
        json = application.json
        provides<BoscaApplication>(singleton = true) { application }
        provides<Json>(singleton = true) { json }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("ObjectBackupRunGraphQLIntegrationTest") }
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<MetadataService> { mockk() }
        StorageProviderRegistrar().register()
        val objectStorage = mockk<ManagedObjectStorageService>()
        provides<ObjectStorageService>(overrideExisting = true) { objectStorage }
        registerQueue()
        BackupProviderRegistrar().register()
        BackupTestProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        runs = provide()
        progress = provide()

        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { test: Boolean }
                type Mutation { test: Boolean }
            """.trimIndent()
        }, BackupSchemaRegistrar())
        graphql = ObjectBackupQueryTestService()
        source = storage("source")
        destination = storage("destination")
        write(source, first.key, "data")
        write(source, second.key, "more")
        run = request { runs.prepare(inventory, "destination-1") }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            stopQueue()
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            DispatchersRegistry.clear()
            ProviderRegistry.clear()
        }
    }

    protected open fun applicationConfig(): ApplicationConfig = ApplicationConfig.load(
        "bosca:\n  server:\n    development: false\n".byteInputStream(),
    )

    protected open suspend fun registerQueue() {
        val queue = mockk<JobQueue>()
        val queueFactory = mockk<JobQueueFactory>()
        every { queueFactory.create(BackupJobQueueNames.backupQueue) } returns queue
        provides<JobQueueFactory> { queueFactory }
    }

    protected open suspend fun stopQueue() = Unit

    protected suspend fun execute(
        query: String,
        authentication: AuthenticationContext = admin,
        variables: JsonObject? = null,
    ): JsonObject = graphql.execute(authentication, GraphQLRequest(query = query, variables = variables)).jsonObject

    protected fun data(response: JsonObject): JsonObject {
        assertFalse("errors" in response, response.toString())
        return response.getValue("data").jsonObject.getValue("backups").jsonObject.getValue("objectRuns").jsonObject
    }

    protected suspend fun complete(value: ObjectBackupRun): ObjectBackupRun =
        runs.execute(value.key, mapOf("content" to source), destination)

    protected suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    protected fun scoped(scopes: List<String>, allowedGroups: Set<bosca.serialization.UUID>? = null): AuthenticationContext {
        val principal = ScopedAuthenticatedPrincipal(Principal(), listOf(adminGroup), scopes, allowedGroups, credentialId = 1)
        return object : AuthenticationContext(null, null) {
            override fun principal(): AuthenticatedPrincipal = principal
        }
    }

    protected fun storage(name: String): ManagedFileSystemObjectStorageService = ManagedFileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    protected suspend fun write(storage: ManagedObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    protected fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject("content", key, "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }

    protected fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
