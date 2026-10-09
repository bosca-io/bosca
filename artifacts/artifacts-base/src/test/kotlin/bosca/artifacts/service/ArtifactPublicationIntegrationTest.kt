@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.service

import bosca.artifacts.github.GitHubPublicationException
import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.github.GitHubReleaseTestServer
import bosca.artifacts.model.*
import bosca.artifacts.repository.*
import bosca.db.*
import bosca.di.*
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pubsub.PubSubService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineSecretServiceImpl
import bosca.pipelines.repository.PipelineSecretRepository
import bosca.pipelines.repository.PipelineSecretRepositoryImpl
import bosca.pipelines.repository.PipelineSecretRepositoryProvider
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.artifacts.pipeline.ArtifactPublicationGetDestinations
import bosca.artifacts.pipeline.ArtifactPublicationNode
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.ArtifactsPipelineNodeSerializersProvider
import bosca.pipelines.node.PipelinesPipelineNodeSerializersProvider
import bosca.pipelines.node.CorePipelinesPipelineNodeSerializersProvider
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineService
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*

/** Production migrations, generated JDBC/DI and pipeline action, encrypted credentials and HTTP adapter. */
class ArtifactPublicationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var nats: SharedNatsContainer
    private lateinit var pool: ConnectionPool
    private lateinit var remote: GitHubReleaseTestServer
    private lateinit var artifacts: ArtifactRepositoryService
    private lateinit var publications: ArtifactPublicationService
    private lateinit var repository: ArtifactPublicationRepository
    private lateinit var blobs: BlobStorageService
    private lateinit var secrets: PipelineSecretService
    private lateinit var artifact: ArtifactRepository
    private lateinit var version: ArtifactVersion
    private lateinit var destination: ArtifactPublicationDestination
    private val bytes = "original build bytes".toByteArray()
    private val digest = GitHubReleaseTestServer.digest(bytes)
    private val commit = "1".repeat(40)
    private val objects = mutableMapOf<String, ByteArray>()

    @BeforeTest fun setup() = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("artifact_publication")
        postgres.start()
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 4,
        ), key = "artifact-publication-test"))
        nats = SharedNatsContainer()
        nats.start()
        val natsPool = nats.newConnectionPool(1)
        remote = GitHubReleaseTestServer()
        ProviderRegistry.register(NamespaceRepository::class, NamespaceRepositoryProvider(), true)
        ProviderRegistry.register(NamespacePermissionRepository::class, NamespacePermissionRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepoRepository::class, ArtifactRepoRepositoryProvider(), true)
        ProviderRegistry.register(VersionRepository::class, VersionRepositoryProvider(), true)
        ProviderRegistry.register(TagRepository::class, TagRepositoryProvider(), true)
        ProviderRegistry.register(UploadSessionRepository::class, UploadSessionRepositoryProvider(), true)
        ProviderRegistry.register(BlobRepository::class, BlobRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactPublicationRepository::class, ArtifactPublicationRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepositoryService::class, ArtifactRepositoryServiceImplProvider(), true)
        ProviderRegistry.register(BlobStorageService::class, BlobStorageServiceImplProvider(), true)
        ProviderRegistry.register(ArtifactPublicationService::class, ArtifactPublicationServiceImplProvider(), true)
        ProviderRegistry.register(PipelineSecretRepository::class, PipelineSecretRepositoryProvider(), true)
        secrets = PipelineSecretServiceImpl(provide())
        provides<PipelineSecretService>(singleton = true) { secrets }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<bosca.cache.CacheManager> { mockk(relaxed = true) }
        provides<bosca.cache.RequestCacheSerializer> { mockk(relaxed = true) }
        val application = BoscaApplication(ApplicationConfig.load("""
            bosca:
              server:
                development: false
        """.trimIndent().byteInputStream()))
        val storage = mockk<ObjectStorageService>()
        coEvery { storage.setInputStream(any(), any(), any()) } coAnswers {
            val data = secondArg<InputStream>().readBytes()
            objects[firstArg<bosca.storage.service.ObjectPath>().toString()] = data
            data.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } coAnswers {
            ByteArrayInputStream(objects.getValue(firstArg<bosca.storage.service.ObjectPath>().toString()))
        }
        coEvery { storage.delete(any()) } coAnswers { objects.remove(firstArg<bosca.storage.service.ObjectPath>().toString()) }
        provides<ObjectStorageService>(singleton = true) { storage }
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
        val locks = NatsDistributedLockFactory(natsPool)
        provides<DistributedLockFactory>(singleton = true) { locks }
        provides<ArtifactPermissionEvaluator>(singleton = true) { mockk(relaxed = true) }
        provides<GitHubReleaseClient>(singleton = true) {
            GitHubReleaseClient(provide(), apiUrl = remote.base, uploadsUrl = remote.base)
        }
        artifacts = provide()
        publications = provide()
        repository = provide()
        blobs = provide()
        withDb {
            connection().useStatement("CREATE TABLE groups(id UUID PRIMARY KEY); CREATE TYPE permission_action AS ENUM ('view','list','edit','manage','delete','execute')") { it.execute() }
            for (name in ArtifactsMigration().resources) {
                val sql = javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
                connection().useStatement(sql) { it.execute() }
            }
            connection().useStatement("CREATE SCHEMA pipelines") { it.execute() }
            val secretMigration = PipelineSecretServiceImpl::class.java.getResource("/db/migrations/V15__pipeline_secret.sql")
                ?.readText() ?: error("Missing Pipeline secret migration")
            connection().useStatement(secretMigration) { it.execute() }
            secrets.setSecret("github-token", "test-token")
            artifact = artifacts.findOrCreateRepository("builds", "tool", ArtifactType.RAW)
            version = artifacts.createVersion(artifact.id, "1.0.0")
            blobs.store(digest, ByteArrayInputStream(bytes), bytes.size.toLong())
            artifacts.addVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
            destination = publications.createDestination(input())
        }
    }

    @AfterTest fun cleanup() = runBlocking {
        if (::remote.isInitialized) remote.close()
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
        if (::nats.isInitialized) nats.stop()
        ProviderRegistry.clear()
    }

    private fun input(key: String = "github", enabled: Boolean = true) =
        ArtifactPublicationDestinationInput(artifact.id, key, 123, "acme", "tool", "github-token", "v", enabled)

    private suspend fun publishThroughPipeline(): NodeResult {
        val event = ArtifactCompleted(UUID.random(), version.id, listOf(UUID.random()), commit, null)
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val selected = assertIs<NodeResult.Output>(ArtifactPublicationGetDestinations("destinations").run(
            context, NodeInputs(mapOf("artifact" to PipelineValue.of(event, ArtifactCompleted.serializer()))),
        ))
        val targets = context.json.decodeFromJsonElement(ListSerializer(ArtifactPublicationTarget.serializer()),
            assertNotNull(selected.value).encode(context.json))
        assertEquals(1, targets.size)
        return ArtifactPublicationNode("publish").run(
            context, NodeInputs(mapOf("target" to PipelineValue.of(targets.single(), ArtifactPublicationTarget.serializer()))),
        )
    }

    @Test fun `pipeline action reuses the committed manifest publishes and verifies the stored bytes`() = runBlocking {
        lateinit var publication: ArtifactPublication
        withDb {
            transaction {
                publication = publications.prepare(destination.id, version.id, commit)
            }
            assertTrue(assertNotNull(artifacts.getVersion(version.id)).finalized)
            assertEquals(listOf(ArtifactPublicationFile("tool.zip", digest, bytes.size.toLong(), "application/zip")), publication.files)
        }
        withDb { assertIs<NodeResult.Output>(publishThroughPipeline()) }
        withDb {
            val result = assertNotNull(repository.find(publication.id))
            assertNotNull(result.published)
            assertNotNull(result.verified)
            assertEquals(42L, result.releaseId)
            assertEquals(1, result.attempts)
            assertNull(result.error)
            assertEquals(publication.id, publications.prepare(destination.id, version.id, commit).id)
        }
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        val requests = remote.requests.size
        withDb { publications.publish(publication.id) }
        assertEquals(requests, remote.requests.size)
    }

    @Test fun `the ordinary executor connects Get Destinations through For Each to the push pipeline`() = withDb {
        val mirror = publications.createDestination(input("mirror"))
        ProviderRegistry.register(PipelineNodeSerializers::class, ArtifactsPipelineNodeSerializersProvider(), "Artifacts", true)
        ProviderRegistry.register(PipelineNodeSerializers::class, PipelinesPipelineNodeSerializersProvider(), "Pipelines", true)
        ProviderRegistry.register(PipelineNodeSerializers::class, CorePipelinesPipelineNodeSerializersProvider(), "CorePipelines", true)
        val body = Pipeline(UUID.random(), "Push artifact", acceptedInputType = "bosca.artifacts.model.ArtifactPublicationTarget",
            nodes = listOf(InputNode("input", acceptedType = "bosca.artifacts.model.ArtifactPublicationTarget"),
                ArtifactPublicationNode("push"), OutputNode("output")),
            edges = listOf(PipelineEdge("input-push", "input", "push", targetPort = "target"),
                PipelineEdge("push-output", "push", "output")))
        val graph = Pipeline(UUID.random(), "Publish completed artifact", acceptedInputType = "bosca.artifacts.model.ArtifactCompleted",
            nodes = listOf(InputNode("input", acceptedType = "bosca.artifacts.model.ArtifactCompleted"),
                ArtifactPublicationGetDestinations("destinations"), ForEach("each", pipelineId = body.id), OutputNode("output")),
            edges = listOf(PipelineEdge("input-destinations", "input", "destinations", targetPort = "artifact"),
                PipelineEdge("destinations-each", "destinations", "each", targetPort = "in"),
                PipelineEdge("each-output", "each", "output")))
        val executor = PipelineExecutorImpl()
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.get(body.id) } returns body
        provides<PipelineService> { pipelines }
        provides<PipelineExecutor> { executor }
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val result = assertIs<ExecutionResult.Completed>(executor.execute(graph,
            PipelineValue.of(ArtifactCompleted(UUID.random(), version.id, emptyList(), commit, null), ArtifactCompleted.serializer()), context))
        assertEquals(2, assertNotNull(result.output).encode(context.json).jsonArray.size)
        val published = publications.publications(version.id)
        assertEquals(setOf(destination.id, mirror.id), published.map { it.destinationId }.toSet())
        assertTrue(published.all { it.published != null && it.verified != null })
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        assertEquals(1, remote.uploads)
    }

    @Test fun `CLI packages checksums and installer publish together with the cli tag prefix`() = withDb {
        publications.updateDestination(destination.id, destination.version, false)
        val cli = publications.createDestination(input("cli").copy(tagPrefix = "cli-v"))
        remote.tag = "cli-v1.0.0"
        val expected = linkedMapOf(
            "bosca-1.0.0-linux-x86_64.tar.gz" to "linux package".toByteArray(),
            "bosca-1.0.0-macos-arm64.pkg" to "macos package".toByteArray(),
            "SHA256SUMS" to "package checksums".toByteArray(),
            "install.sh" to "installer script".toByteArray(),
        )
        artifacts.deleteVersion(version.id)
        version = artifacts.createVersion(artifact.id, "1.0.0")
        for ((filename, data) in expected) {
            val hash = GitHubReleaseTestServer.digest(data)
            blobs.store(hash, data.inputStream(), data.size.toLong())
            artifacts.addVersionBlob(version.id, hash, "file", filename, "application/octet-stream")
        }
        assertIs<NodeResult.Output>(publishThroughPipeline())
        val result = publications.publications(version.id).single()
        assertEquals(cli.id, result.destinationId)
        assertEquals("cli-v1.0.0", result.tagName)
        assertNotNull(result.verified)
        assertEquals(expected.keys, result.files.map { it.filename }.toSet())
        assertEquals(expected.keys, remote.files.keys)
        for ((filename, data) in expected) assertContentEquals(data, remote.files.getValue(filename))
    }

    @Test fun `outer rollback leaves no finalized version or publication`() = runBlocking {
        withDb {
            assertFailsWith<IllegalStateException> {
                transaction {
                    publications.prepare(destination.id, version.id, commit)
                    error("roll back")
                }
            }
            assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
            assertTrue(publications.publications(version.id).isEmpty())
        }
    }

    @Test fun `disabled destinations prevent finalization and publication until enabled`() = withDb {
        destination = publications.updateDestination(destination.id, destination.version, false)
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        destination = publications.updateDestination(destination.id, destination.version, true)
        val publication = publications.prepare(destination.id, version.id, commit)
        destination = publications.updateDestination(destination.id, destination.version, false)
        assertFailsWith<IllegalStateException> { publications.publish(publication.id) }
        assertNull(assertNotNull(repository.find(publication.id)).published)
    }

    @Test fun `response loss retries reuse original stored bytes and completed remote assets`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        remote.uploadFailures = 1
        remote.loseUploadResponse = true
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id) }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(1, failed.attempts)
        assertNull(failed.published)
        assertNull(failed.verified)
        assertEquals("GitHub artifact publication failed: HTTP 502", failed.error)
        assertTrue(assertNotNull(artifacts.getVersion(version.id)).finalized)
        publications.publish(publication.id)
        assertNotNull(assertNotNull(repository.find(publication.id)).verified)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.creates)
        assertEquals(0, remote.deletes)
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
    }

    @Test fun `publication success remains durable when independent verification fails`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        remote.verificationFailures = 1
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id) }
        val published = assertNotNull(repository.find(publication.id))
        assertNotNull(published.published)
        assertEquals(42L, published.releaseId)
        assertNull(published.verified)
        assertEquals(1, published.attempts)
        assertNotNull(published.error)
        publications.publish(publication.id)
        val verified = assertNotNull(repository.find(publication.id))
        assertEquals(published.published, verified.published)
        assertNotNull(verified.verified)
        assertNull(verified.error)
        assertEquals(2, verified.attempts)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.publications)
    }

    @Test fun `fixed manifests reject changed bytes new files metadata and different publication inputs`() = withDb {
        publications.prepare(destination.id, version.id, commit)
        artifacts.addVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        artifacts.addOrReplaceVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        assertFailsWith<IllegalArgumentException> { artifacts.addOrReplaceVersionBlob(version.id, digest, "file", "tool.zip", "text/plain") }
        assertFailsWith<IllegalArgumentException> { artifacts.addVersionBlob(version.id, digest, "other", "other.zip", "application/zip") }
        assertFailsWith<IllegalArgumentException> { artifacts.addOrReplaceVersionBlob(version.id, "sha256:" + "0".repeat(64), "file", "tool.zip", "application/zip") }
        assertEquals(1, assertNotNull(blobs.get(digest)).refCount)
        assertEquals(1, artifacts.getVersionBlobs(version.id).size)
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, "2".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit, true) }
        assertEquals(1, publications.publications(version.id).size)
    }

    @Test fun `unrequested fixed path uploads remain replaceable`() = withDb {
        val nextBytes = "next build bytes".toByteArray()
        val nextDigest = GitHubReleaseTestServer.digest(nextBytes)
        blobs.store(nextDigest, ByteArrayInputStream(nextBytes), nextBytes.size.toLong())
        artifacts.addOrReplaceVersionBlob(version.id, nextDigest, "file", "tool.zip", "application/zip")
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertEquals(nextDigest, artifacts.getVersionBlobs(version.id).single().digest)
        assertNull(blobs.get(digest))
    }

    @Test fun `preparing one selected destination does not create publications for other matches`() = withDb {
        val second = publications.createDestination(input("mirror"))
        val first = publications.prepare(destination.id, version.id, commit)
        assertEquals(listOf(first.id), publications.publications(version.id).map { it.id })
        assertNull(repository.find(second.id, version.id))
        val mirror = publications.prepare(second.id, version.id, commit)
        assertEquals(2, publications.publications(version.id).size)
        assertEquals(first.id, publications.prepare(destination.id, version.id, commit).id)
        assertEquals(mirror.id, publications.prepare(second.id, version.id, commit).id)
    }

    @Test fun `a destination cannot accept an artifact from a different repository`() = withDb {
        val other = artifacts.findOrCreateRepository("builds", "other", ArtifactType.RAW)
        val unrelated = publications.createDestination(input("unrelated").copy(repositoryId = other.id))
        assertFailsWith<IllegalArgumentException> { publications.prepare(unrelated.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertTrue(publications.publications(version.id).isEmpty())
    }

    @Test fun `changing destinations preserves the version's original commit and prerelease choice`() = withDb {
        val original = publications.prepare(destination.id, version.id, commit)
        destination = publications.updateDestination(destination.id, destination.version, false)
        val second = publications.createDestination(input("replacement"))
        assertFailsWith<IllegalArgumentException> { publications.prepare(second.id, version.id, "2".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { publications.prepare(second.id, version.id, commit, true) }
        val replacement = publications.prepare(second.id, version.id, commit)
        assertEquals(second.id, replacement.destinationId)
        assertEquals(original.commitSha, replacement.commitSha)
        assertEquals(original.prerelease, replacement.prerelease)
        assertEquals(original.files, replacement.files)
    }

    @Test fun `pipeline retries retain the publication identity and reuse remote assets after response loss`() = withDb {
        remote.uploadFailures = 1
        remote.loseUploadResponse = true
        assertFailsWith<GitHubPublicationException> { publishThroughPipeline() }
        val original = publications.publications(version.id).single()
        assertNull(original.verified)
        assertIs<NodeResult.Output>(publishThroughPipeline())
        val retried = publications.publications(version.id).single()
        assertEquals(original.id, retried.id)
        assertNotNull(retried.verified)
        assertEquals(1, remote.creates)
        assertEquals(1, remote.uploads)
    }

    @Test fun `existing encrypted Pipeline secrets are reused rotated and referenced under destination version comparisons`() = withDb {
        val stored = assertNotNull(PipelineSecretRepositoryImpl().findByName(destination.tokenSecretName))
        assertFalse(stored.encryptedValue.contains("test-token"))
        assertEquals(destination, publications.destinations(artifact.id).single())
        assertNotNull(destination.created)
        secrets.setSecret("rotated-secret", "rotated-token")
        val rotated = publications.updateDestination(destination.id, 0, true, "rotated-secret")
        assertEquals(1, rotated.version)
        assertEquals("rotated-secret", rotated.tokenSecretName)
        assertFailsWith<IllegalStateException> { publications.updateDestination(destination.id, 0, false, "stale-secret") }
        val publication = publications.prepare(destination.id, version.id, commit)
        val before = remote.requests.size
        publications.publish(publication.id)
        assertTrue(remote.requests.drop(before).all { it.headers["Authorization"] == "Bearer rotated-token" })
    }

    @Test fun `secret value rotation is resolved at publication time without changing the destination`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        secrets.setSecret(destination.tokenSecretName, "new-secret-value")
        val before = remote.requests.size
        publications.publish(publication.id)
        assertTrue(remote.requests.drop(before).all { it.headers["Authorization"] == "Bearer new-secret-value" })
        assertEquals(destination, publications.destinations(artifact.id).single())
    }

    @Test fun `missing credentials fail before a remote release is created`() = withDb {
        assertFailsWith<IllegalStateException> {
            publications.createDestination(input().copy(tokenSecretName = "missing-secret"))
        }
        val publication = publications.prepare(destination.id, version.id, commit)
        secrets.deleteSecret(destination.tokenSecretName)
        assertFailsWith<IllegalStateException> { publications.publish(publication.id) }
        assertEquals(0, remote.creates)
        assertNull(assertNotNull(repository.find(publication.id)).verified)
    }

    @Test fun `invalid filenames and media types roll back finalization`() = withDb {
        for ((name, media) in listOf("bad name.zip" to "application/zip", "tool.zip" to "bad type")) {
            val invalid = artifacts.createVersion(artifact.id, UUID.random().toString())
            artifacts.addVersionBlob(invalid.id, digest, "file", name, media)
            assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, invalid.id, commit) }
            assertFalse(assertNotNull(artifacts.getVersion(invalid.id)).finalized)
            assertTrue(publications.publications(invalid.id).isEmpty())
        }
    }

    @Test fun `explicit local deletion cancels publication work without deleting the remote release`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        artifacts.deleteVersion(version.id)
        assertNull(repository.find(publication.id))
        val requests = remote.requests.size
        publications.publish(publication.id)
        assertEquals(requests, remote.requests.size)
        assertTrue(remote.hasRelease)
        assertEquals(0, remote.deletes)
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try { withContext(manager.asCoroutineContext()) { block() } }
        finally { withContext(NonCancellable) { manager.release() } }
    }
}
