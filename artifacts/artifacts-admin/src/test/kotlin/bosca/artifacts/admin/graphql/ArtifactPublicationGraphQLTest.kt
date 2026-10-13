@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.admin.graphql

import bosca.artifacts.model.*
import bosca.artifacts.service.*
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.*
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.observability.ErrorCapture
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

/** Executes the complete module SDL through generated field dispatchers. */
class ArtifactPublicationGraphQLTest {
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val publications = mockk<ArtifactPublicationService>()
    private val syncing = mockk<ArtifactSyncService>()
    private val principal = Principal(id = UUID.random())
    private val admin = ImpersonatedAuthenticationContext(principal, listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)))
    private val ordinary = ImpersonatedAuthenticationContext(principal, emptyList())
    private val destination = ArtifactPublicationDestination(
        UUID.random(), UUID.random(), "github", 123, "acme", "tool", "v", false,
        "github-token", created = OffsetDateTime.now(), modified = OffsetDateTime.now()
    )
    private val publication = ArtifactPublication(
        UUID.random(), destination.id, UUID.random(), "v1.0.0", "1".repeat(40), false,
        listOf(ArtifactPublicationFile("tool.zip", "sha256:" + "a".repeat(64), 12, "application/zip")),
        attempts = 1, error = "HTTP 503", created = OffsetDateTime.now(), modified = OffsetDateTime.now()
    )
    private val storedVersion = ArtifactVersion(publication.versionId, destination.repositoryId, "1.0.0", completedCommitSha = publication.commitSha)
    private val storedBlob = ArtifactVersionBlob(publication.versionId, publication.files.single().digest, "file", "tool.zip", "application/zip")
    private val syncDestination = ArtifactSyncDestination(
        UUID.random(), UUID.random(), "ghcr", "acme/server", "acme",
        "ghcr-token", created = OffsetDateTime.now(), modified = OffsetDateTime.now()
    )
    private val sync = ArtifactSync(
        UUID.random(), syncDestination.id, UUID.random(), "latest", "sha256:" + "a".repeat(64),
        attempts = 1, error = "HTTP 503", created = OffsetDateTime.now(), modified = OffsetDateTime.now()
    )
    private lateinit var graph: GraphQLService

    @BeforeTest
    fun setup(): Unit = runBlocking {
        ProviderRegistry.clear()
        BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        provides<ConnectionPool> { mockk(relaxed = true) }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("ArtifactPublicationGraphQLTest") }
        provides<ArtifactRepositoryService> { artifacts }
        provides<ArtifactPublicationService> { publications }
        val groups = GroupEvaluator(mockk())
        val schema = javaClass.getResource("/graphql/artifacts-admin.graphqls")?.readText() ?: error("Missing artifact schema")
        SchemaRegistry.initialize(object : bosca.graphql.SchemaRegistrar {
            override suspend fun load() = """
                scalar UUID
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                enum PermissionAction { VIEW LIST EDIT MANAGE DELETE EXECUTE }
                input PermissionInput { entityId: UUID!, groupId: UUID!, action: PermissionAction! }
                type Permission { groupId: UUID! }
                type Query { _empty: Boolean }
                type Mutation { _empty: Boolean }
                $schema
            """.trimIndent()
        })
        val root = object : SchemaRoot {
            override val query = object : QueryRoot {}
            override val mutation = object : MutationRoot {}
            override val subscription = object : SubscriptionRoot {}
        }
        graph = object : GraphQLService(root, false) {
            override val dispatchersRegistrar = object : DispatchersRegistrar {
                override suspend fun dispatchers(): Map<String, Dispatcher> = listOf(
                    ArtifactsAdminControllerDispatcher(ArtifactsAdminController(artifacts, mockk(), publications, groups, syncing)),
                    ArtifactsAdminMutationControllerDispatcher(ArtifactsAdminMutationController(artifacts, groups, publications, syncing)),
                    ArtifactSyncControllerDispatcher(ArtifactSyncController()),
                    ArtifactSyncDestinationControllerDispatcher(ArtifactSyncDestinationController()),
                    ArtifactPublicationControllerDispatcher(ArtifactPublicationController()),
                    ArtifactPublicationDestinationControllerDispatcher(ArtifactPublicationDestinationController()),
                    ArtifactPublicationFileControllerDispatcher(ArtifactPublicationFileController()),
                    ArtifactVersionInfoControllerDispatcher(ArtifactVersionInfoController(artifacts, groups)),
                    ArtifactVersionBlobInfoControllerDispatcher(ArtifactVersionBlobInfoController(mockk(), groups)),
                ).associateBy { it.type.typeName }
            }

            override suspend fun initialize(builder: RuntimeWiringBuilder) {
                builder.type("Query") { field("artifactsAdmin") { ArtifactsAdmin } }
                builder.type("Mutation") { field("artifactsAdmin") { ArtifactsAdminMutation } }
            }
        }
        coEvery { publications.createDestination(any()) } returns destination
        coEvery { publications.updateDestination(destination.id, 0, true, "rotated") } returns destination.copy(enabled = true, version = 1)
        coEvery { publications.destinations(destination.repositoryId) } returns listOf(destination)
        coEvery { publications.publications(publication.versionId, 100, 0) } returns listOf(publication)
        coEvery { publications.publish(any(), destination.id, publication.versionId, publication.commitSha) } returns publication.id
        coEvery { artifacts.getVersion(publication.versionId) } returns storedVersion
        coEvery { artifacts.getVersionBlobs(publication.versionId) } returns listOf(storedBlob)
        coEvery { artifacts.confirmVersionCompletion(publication.versionId, publication.commitSha, listOf(storedBlob), principal.id) } returns storedVersion
        coEvery { syncing.createDestination(any()) } returns syncDestination
        coEvery { syncing.updateDestination(syncDestination.id, 0, true, null, "rotated", null, null) } returns syncDestination.copy(enabled = true, version = 1)
        coEvery { syncing.updateDestination(syncDestination.id, 0, true, null, null, "renamed", "acme/renamed") } returns syncDestination.copy(key = "renamed", remoteRepository = "acme/renamed", enabled = true, version = 1)
        coEvery { syncing.destinations(syncDestination.repositoryId) } returns listOf(syncDestination)
        coEvery { syncing.syncs(syncDestination.repositoryId, 100, 0) } returns listOf(sync)
        coEvery { syncing.retry(sync.id) } returns sync.copy(error = null)
        coEvery { syncing.deleteDestination(syncDestination.id, 0) } returns Unit
        coEvery { syncing.push(any(), syncDestination.id, "latest") } returns sync.id
    }

    @AfterTest
    fun cleanup() = ProviderRegistry.clear()

    private val destinationFields = "id repositoryId key githubRepositoryId owner githubRepository tagPrefix tokenSecretName enabled version created modified"
    private val publicationFields = "id destinationId versionId tagName commitSha prerelease releaseId attempts published verified error created modified files { filename digest size mediaType }"
    private val syncDestinationFields = "id repositoryId key remoteRepository username tokenSecretName enabled version created modified"
    private val syncFields = "id destinationId versionId tagName manifestDigest attempts synced error created modified"
    private val operations: List<String>
        get() = listOf(
            """mutation { artifactsAdmin { createPublicationDestination(input: {
            repositoryId: "${destination.repositoryId}", key: "github", githubRepositoryId: 123, owner: "acme", githubRepository: "tool", tokenSecretName: "github-token"
        }) { $destinationFields } } }""",
            """mutation { artifactsAdmin { updatePublicationDestination(id: "${destination.id}", version: 0, enabled: true, tokenSecretName: "rotated") { $destinationFields } } }""",
            """mutation { artifactsAdmin { publishRelease(destinationId: "${destination.id}", versionId: "${publication.versionId}", commitSha: "${publication.commitSha}") } }""",
            """{ artifactsAdmin { version(id: "${publication.versionId}") { id completed finalized completedCommitSha blobs { digest role filename mediaType } } } }""",
            """mutation { artifactsAdmin { completeVersion(versionId: "${publication.versionId}", commitSha: "${publication.commitSha}", expectedBlobs: [{
                versionId: "${publication.versionId}", digest: "${storedBlob.digest}", role: "file", filename: "tool.zip", mediaType: "application/zip"
            }]) { id completed finalized completedCommitSha } } }""",
            """{ artifactsAdmin { publicationDestinations(repositoryId: "${destination.repositoryId}") { $destinationFields }
            publications(versionId: "${publication.versionId}") { $publicationFields } } }""",
            """mutation { artifactsAdmin { createSyncDestination(input: {
            repositoryId: "${syncDestination.repositoryId}", key: "ghcr", remoteRepository: "acme/server", username: "acme", tokenSecretName: "ghcr-token"
        }) { $syncDestinationFields } } }""",
            """mutation { artifactsAdmin { updateSyncDestination(id: "${syncDestination.id}", version: 0, enabled: true, tokenSecretName: "rotated") { $syncDestinationFields } } }""",
            """mutation { artifactsAdmin { updateSyncDestination(id: "${syncDestination.id}", version: 0, enabled: true, key: "renamed", remoteRepository: "acme/renamed") { $syncDestinationFields } } }""",
            """mutation { artifactsAdmin { retrySync(id: "${sync.id}") { $syncFields } } }""",
            """mutation { artifactsAdmin { deleteSyncDestination(id: "${syncDestination.id}", version: 0) } }""",
            """mutation { artifactsAdmin { pushImage(destinationId: "${syncDestination.id}", tagName: "latest") } }""",
            """{ artifactsAdmin { syncDestinations(repositoryId: "${syncDestination.repositoryId}") { $syncDestinationFields }
            syncs(repositoryId: "${syncDestination.repositoryId}") { $syncFields } } }""",
        )

    @Test
    fun `admin resolves all publication fields and omitted input defaults through generated wiring`() = runBlocking {
        for (query in operations) {
            val result = graph.execute(admin, GraphQLRequest(query = query)).jsonObject
            assertFalse("errors" in result, result.toString())
            assertFalse(result.toString().contains("must-not-be-exposed"))
        }
        coVerify { publications.createDestination(match { !it.enabled && it.tagPrefix.isEmpty() && it.tokenSecretName == "github-token" }) }
        coVerify { syncing.createDestination(match { !it.enabled && it.remoteRepository == "acme/server" && it.tokenSecretName == "ghcr-token" }) }
        coVerify(exactly = 1) { syncing.deleteDestination(syncDestination.id, 0) }
        coVerify(exactly = 1) { syncing.updateDestination(syncDestination.id, 0, true, null, null, "renamed", "acme/renamed") }
        coVerify(exactly = 1) { syncing.push(any(), syncDestination.id, "latest") }
        coVerify(exactly = 1) { publications.publish(admin, destination.id, publication.versionId, publication.commitSha) }
        coVerify(exactly = 1) { artifacts.confirmVersionCompletion(publication.versionId, publication.commitSha, listOf(storedBlob), principal.id) }
    }

    @Test
    fun `ordinary callers cannot read or configure remote publication`() = runBlocking {
        for (query in operations) {
            val result = graph.execute(ordinary, GraphQLRequest(query = query)).jsonObject
            assertTrue("errors" in result, result.toString())
        }
        coVerify(exactly = 0) { publications.createDestination(any()) }
        coVerify(exactly = 0) { publications.updateDestination(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.destinations(any()) }
        coVerify(exactly = 0) { publications.publications(any(), any(), any()) }
        coVerify(exactly = 0) { publications.publish(any(), any(), any(), any()) }
        coVerify(exactly = 0) { artifacts.confirmVersionCompletion(any(), any(), any(), any()) }
        coVerify(exactly = 0) { artifacts.getVersion(any()) }
        coVerify(exactly = 0) { syncing.createDestination(any()) }
        coVerify(exactly = 0) { syncing.updateDestination(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncing.destinations(any()) }
        coVerify(exactly = 0) { syncing.syncs(any(), any(), any()) }
        coVerify(exactly = 0) { syncing.retry(any()) }
        coVerify(exactly = 0) { syncing.deleteDestination(any(), any()) }
        coVerify(exactly = 0) { syncing.push(any(), any(), any()) }
    }

    @Test
    fun `credentials are absent from the public GraphQL destination type`() = runBlocking {
        for (field in listOf("encryptedToken", "tokenNonce")) {
            val result = graph.execute(
                admin, GraphQLRequest(
                    query = """{ artifactsAdmin {
                publicationDestinations(repositoryId: "${destination.repositoryId}") { $field }
            } }"""
                )
            ).jsonObject
            assertTrue("errors" in result, result.toString())
        }
        coVerify(exactly = 0) { publications.destinations(any()) }
    }
}
