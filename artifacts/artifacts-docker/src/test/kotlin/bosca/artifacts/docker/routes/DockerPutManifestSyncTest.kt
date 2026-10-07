@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.artifacts.model.ArtifactRepository
import bosca.artifacts.model.ArtifactVersion
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.service.AuthenticationProviders
import bosca.serialization.UUID
import bosca.server.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.*

/** The tagged manifest boundary finishes storing the manifest before the ordinary service tag write. */
class DockerPutManifestSyncTest {
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val blobs = mockk<BlobStorageService>(relaxed = true)
    private val permissions = mockk<ArtifactPermissionEvaluator>()
    private val manager = mockk<ConnectionManager>(relaxed = true)
    private val repository = ArtifactRepository(UUID.random(), UUID.random(), "server", "docker")
    private val bytes = """{"schemaVersion":2,"config":{},"layers":[]}""".toByteArray()
    private val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val version = ArtifactVersion(UUID.random(), repository.id, digest)
    private val call = mockk<ServerCall>(relaxed = true)

    private fun setup(reference: String) {
        ProviderRegistry.clear()
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } returns manager
        provides<ConnectionPool> { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        every { call.authenticationContext } returns mockk(relaxed = true)
        every { call.response.status() } returns HttpStatusCode.Created
        every { call.response.isCommitted } returns true
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("namespace" to "images", "repo" to "server", "reference" to reference))
        coEvery { permissions.evaluate(any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { call.request.bodyStreamTo(any()) } coAnswers { firstArg<OutputStream>().write(bytes); bytes.size.toLong() }
        every { call.request.contentType() } returns null
        coEvery { artifacts.findOrCreateRepository("images", "server", ArtifactType.DOCKER) } returns repository
        coEvery { artifacts.findVersion(repository.id, digest) } returns null
        coEvery { artifacts.createVersion(repository.id, digest, any()) } returns version
        coEvery { artifacts.addVersionBlob(version.id, digest, "manifest", null, null) } just Runs
        coEvery { artifacts.setTag(repository.id, any(), digest) } returns mockk(relaxed = true)
    }

    @AfterTest
    fun cleanup() = ProviderRegistry.clear()

    private suspend fun execute() = DockerPutManifest(artifacts, blobs, permissions).execute(call)

    @Test
    fun `repository creation precedes the transaction and the manifest is stored before tagging`(): Unit = runBlocking {
        setup("latest")
        execute()
        coVerifyOrder {
            artifacts.findOrCreateRepository("images", "server", ArtifactType.DOCKER)
            manager.beginTransaction()
            artifacts.addVersionBlob(version.id, digest, "manifest", null, null)
            artifacts.setTag(repository.id, "latest", digest)
            manager.commitTransaction()
            call.respond(HttpStatusCode.Created)
        }
    }

    @Test
    fun `digest-only child manifest uploads do not request a remote tag`(): Unit = runBlocking {
        setup(digest)
        execute()
        coVerify(exactly = 0) { artifacts.setTag(any(), any(), any()) }
    }

    @Test
    fun `interrupted uploads never schedule a sync`(): Unit = runBlocking {
        setup("latest")
        every { call.response.isCommitted } returns false
        every { call.application.json } returns kotlinx.serialization.json.Json
        coEvery { call.request.bodyStreamTo(any()) } throws IllegalStateException("interrupted")
        execute()
        coVerify { call.response.respondText(any(), ContentType.Application.Json, HttpStatusCode.BadRequest) }
        coVerify(exactly = 0) { artifacts.createVersion(any(), any(), any()) }
    }
}
