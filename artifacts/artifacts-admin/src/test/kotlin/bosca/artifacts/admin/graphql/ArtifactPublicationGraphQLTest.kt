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
    private val principal = Principal(id = UUID.random())
    private val admin = ImpersonatedAuthenticationContext(principal, listOf(Group(name = "administrators", description = "", type = GroupType.SYSTEM)))
    private val ordinary = ImpersonatedAuthenticationContext(principal, emptyList())
    private val destination = ArtifactPublicationDestination(UUID.random(), UUID.random(), "github", 123, "acme", "tool", "v", false,
        "github-token", created = OffsetDateTime.now(), modified = OffsetDateTime.now())
    private val publication = ArtifactPublication(UUID.random(), destination.id, UUID.random(), "v1.0.0", "1".repeat(40), false,
        listOf(ArtifactPublicationFile("tool.zip", "sha256:" + "a".repeat(64), 12, "application/zip")),
        attempts = 1, error = "HTTP 503", created = OffsetDateTime.now(), modified = OffsetDateTime.now())
    private lateinit var graph: GraphQLService

    @BeforeTest fun setup(): Unit = runBlocking {
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
                    ArtifactsAdminControllerDispatcher(ArtifactsAdminController(artifacts, mockk(), publications, groups)),
                    ArtifactsAdminMutationControllerDispatcher(ArtifactsAdminMutationController(artifacts, groups, publications)),
                    ArtifactPublicationControllerDispatcher(ArtifactPublicationController()),
                    ArtifactPublicationDestinationControllerDispatcher(ArtifactPublicationDestinationController()),
                    ArtifactPublicationFileControllerDispatcher(ArtifactPublicationFileController()),
                    ArtifactVersionInfoControllerDispatcher(ArtifactVersionInfoController(artifacts, groups)),
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
    }

    @AfterTest fun cleanup() = ProviderRegistry.clear()

    private val destinationFields = "id repositoryId key githubRepositoryId owner githubRepository tagPrefix tokenSecretName enabled version created modified"
    private val publicationFields = "id destinationId versionId tagName commitSha prerelease releaseId attempts published verified error created modified files { filename digest size mediaType }"
    private val operations: List<String> get() = listOf(
        """mutation { artifactsAdmin { createPublicationDestination(input: {
            repositoryId: "${destination.repositoryId}", key: "github", githubRepositoryId: 123, owner: "acme", githubRepository: "tool", tokenSecretName: "github-token"
        }) { $destinationFields } } }""",
        """mutation { artifactsAdmin { updatePublicationDestination(id: "${destination.id}", version: 0, enabled: true, tokenSecretName: "rotated") { $destinationFields } } }""",
        """{ artifactsAdmin { publicationDestinations(repositoryId: "${destination.repositoryId}") { $destinationFields }
            publications(versionId: "${publication.versionId}") { $publicationFields } } }""",
    )

    @Test fun `admin resolves all publication fields and omitted input defaults through generated wiring`() = runBlocking {
        for (query in operations) {
            val result = graph.execute(admin, GraphQLRequest(query = query)).jsonObject
            assertFalse("errors" in result, result.toString())
            assertFalse(result.toString().contains("must-not-be-exposed"))
        }
        coVerify { publications.createDestination(match { !it.enabled && it.tagPrefix.isEmpty() && it.tokenSecretName == "github-token" }) }
    }

    @Test fun `ordinary callers cannot read or configure remote publication`() = runBlocking {
        for (query in operations) {
            val result = graph.execute(ordinary, GraphQLRequest(query = query)).jsonObject
            assertTrue("errors" in result, result.toString())
        }
        coVerify(exactly = 0) { publications.createDestination(any()) }
        coVerify(exactly = 0) { publications.updateDestination(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.destinations(any()) }
        coVerify(exactly = 0) { publications.publications(any(), any(), any()) }
    }

    @Test fun `credentials are absent from the public GraphQL destination type`() = runBlocking {
        for (field in listOf("encryptedToken", "tokenNonce")) {
            val result = graph.execute(admin, GraphQLRequest(query = """{ artifactsAdmin {
                publicationDestinations(repositoryId: "${destination.repositoryId}") { $field }
            } }""")).jsonObject
            assertTrue("errors" in result, result.toString())
        }
        coVerify(exactly = 0) { publications.destinations(any()) }
    }
}
