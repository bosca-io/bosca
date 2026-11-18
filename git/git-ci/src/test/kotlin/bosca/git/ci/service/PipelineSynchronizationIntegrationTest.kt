@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.withRequestCache
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.db.withConnectionManager
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.repository.PipelineRepository
import bosca.git.ci.repository.PipelineRepositoryImpl
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.repository.DfsPackRepositoryImpl
import bosca.git.repository.DfsRefRepositoryImpl
import bosca.git.service.PipelineScheduleService
import bosca.git.service.RepositoryBrowseServiceImpl
import bosca.git.service.RepositoryService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import java.io.InputStream
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises real Git DFS reads, generated SQL, and catalog locking with fewer connections than callers. */
class PipelineSynchronizationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var manager: BoscaDfsRepositoryManager
    private lateinit var browse: RepositoryBrowseServiceImpl
    private lateinit var json: Json
    private lateinit var commitSha: String
    private val repositoryId = UUID.random()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val repositories = mockk<RepositoryService>()
    private val schedules = mockk<PipelineScheduleService>(relaxed = true)

    @BeforeTest
    fun setup() = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("ci_catalog_pool")
        postgres.start()
        json = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream())).json
        configurePool(2)
        withConnectionManager {
            sql("create type profile_type as enum ('generic', 'organization'); " +
                "create type permission_action as enum ('view', 'edit', 'delete', 'manage', 'list', 'execute'); " +
                "create table groups (id uuid primary key)")
            for (name in listOf("V1__git_server.sql", "V12__ci_cd_pipelines.sql",
                "V23__dfs_pack_soft_delete.sql", "V43__pipeline_catalog_archival.sql")) {
                sql(javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name"))
            }
            sql("insert into git.repositories (id, slug, name, owner_id, owner_type) " +
                "values ('$repositoryId', 'source', 'Source', '${UUID.random()}', 'generic')")
        }
        coEvery { repositories.findById(repositoryId) } returns Repository(
            id = repositoryId, slug = "source", name = "Source", ownerId = UUID.random(),
        )
        val storage = mockk<ObjectStorageService>()
        coEvery { storage.setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<InputStream>().readBytes()
            files[firstArg<ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } answers {
            files.getValue(firstArg<ObjectPath>().toString()).inputStream()
        }
        coEvery { storage.getInputStreamRange(any(), any()) } answers {
            val range = secondArg<LongRange>()
            files.getValue(firstArg<ObjectPath>().toString())
                .copyOfRange(range.first.toInt(), range.last.toInt() + 1).inputStream()
        }
        val packs = DfsPackRepositoryImpl()
        manager = BoscaDfsRepositoryManager(storage, packs, DfsRefRepositoryImpl())
        browse = RepositoryBrowseServiceImpl(manager, packs, mockk(relaxed = true))
        commitSha = createCommit()
    }

    @AfterTest
    fun cleanup() = runBlocking {
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
        ProviderRegistry.clear()
    }

    private suspend fun configurePool(limit: Int) {
        if (::pool.isInitialized) pool.close()
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl + separator + "socketTimeout=5&options=-c%20statement_timeout%3D2000",
            user = postgres.username, password = postgres.password, maxConnections = limit,
        ), "ci-catalog-pool"))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
    }

    private fun service(repository: PipelineRepository = PipelineRepositoryImpl()) =
        PipelineServiceImpl(repository, browse, PipelineYamlParser(), json, repositories, schedules)

    private suspend fun synchronize(service: PipelineServiceImpl) = withConnectionManager {
        withRequestCache { service.syncPipelines(repositoryId, "refs/heads/main", commitSha) }
    }

    @Test
    fun `catalog and real Git reads complete with one pooled connection`() = runBlocking {
        configurePool(1)
        val result = withTimeout(10_000) { synchronize(service()) }
        assertEquals("Build", result.single().name)
        val persisted = withConnectionManager { PipelineRepositoryImpl().findByRepository(repositoryId) }
        assertEquals(result, persisted)
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `lock contenders release connections and cancellation leaves catalog synchronization usable`() = runBlocking {
        val failedAttempts = Channel<Unit>(Channel.UNLIMITED)
        val sqlRepository = PipelineRepositoryImpl()
        val observedRepository = object : PipelineRepository by sqlRepository {
            override suspend fun tryLockForSync(repositoryId: UUID): Boolean {
                val acquired = sqlRepository.tryLockForSync(repositoryId)
                if (!acquired) failedAttempts.trySend(Unit)
                return acquired
            }
        }
        val service = service(observedRepository)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { holder ->
            holder.autoCommit = false
            holder.prepareStatement("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))").use {
                it.setString(1, repositoryId.toString())
                it.execute()
            }
            withTimeout(20_000) {
                coroutineScope {
                    val requests = List(16) { async { synchronize(service) } }
                    repeat(requests.size) { failedAttempts.receive() }
                    // This is the same kind of query readiness needs while CI is contending.
                    withTimeout(1_000) { withConnectionManager { sql("select 1") } }
                    assertFalse(requests.any { it.isCompleted })
                    requests.take(8).forEach { it.cancelAndJoin() }
                    withTimeout(1_000) { withConnectionManager { sql("select 1") } }
                    holder.commit()
                    val results = requests.drop(8).awaitAll()
                    assertEquals(1, results.map { it.single().id }.distinct().size)
                    assertTrue(results.all { it.single().name == "Build" })
                }
            }
        }
        assertEquals(0, pool.activeConnections)
        assertEquals(1, withConnectionManager { PipelineRepositoryImpl().findByRepository(repositoryId).size })
    }

    private suspend fun sql(statement: String) {
        connection().useStatement(statement) { it.execute() }
    }

    private fun createCommit(): String = manager.open(repositoryId).use { repository ->
        repository.newObjectInserter().use { inserter ->
            val yaml = """
                name: Build
                on:
                  push:
                    branches: [main]
                jobs:
                  build:
                    steps:
                      - name: Test
                        run: echo test
            """.trimIndent()
            val blob = inserter.insert(Constants.OBJ_BLOB, yaml.toByteArray())
            val pipelines = inserter.insert(TreeFormatter().apply { append("build.yaml", FileMode.REGULAR_FILE, blob) })
            val bosca = inserter.insert(TreeFormatter().apply { append("pipelines", FileMode.TREE, pipelines) })
            val root = inserter.insert(TreeFormatter().apply { append(".bosca", FileMode.TREE, bosca) })
            val person = PersonIdent("Test", "test@example.com")
            val commit = inserter.insert(CommitBuilder().apply {
                setTreeId(root)
                setAuthor(person)
                setCommitter(person)
                setMessage("Build pipeline")
            })
            inserter.flush()
            repository.updateRef("refs/heads/main").apply { setNewObjectId(commit); update() }
            commit.name()
        }
    }
}
