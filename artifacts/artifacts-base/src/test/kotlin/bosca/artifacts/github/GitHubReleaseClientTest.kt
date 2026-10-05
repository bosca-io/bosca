package bosca.artifacts.github

import bosca.artifacts.model.*
import bosca.artifacts.service.BlobStorageService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.ByteArrayInputStream
import kotlin.test.*

class GitHubReleaseClientTest {
    private val bytes = "release bytes".toByteArray()
    private val digest = GitHubReleaseTestServer.digest(bytes)
    private val destination = ArtifactPublicationDestination(UUID.random(), UUID.random(), "github", 123,
        "acme", "tool", "v", true, "github-token")
    private val publication = ArtifactPublication(UUID.random(), destination.id, UUID.random(), "v1.0.0", "1".repeat(40), false,
        listOf(ArtifactPublicationFile("tool.zip", digest, bytes.size.toLong(), "application/zip")))
    private val blobs = mockk<BlobStorageService>()

    private fun client(remote: GitHubReleaseTestServer): GitHubReleaseClient {
        coEvery { blobs.getInputStream(digest) } answers { ByteArrayInputStream(bytes) }
        return GitHubReleaseClient(blobs, apiUrl = remote.base, uploadsUrl = remote.base)
    }

    @Test fun `creates draft streams original bytes publishes then verifies without replacing assets`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            val github = client(remote)
            assertEquals(42, github.publish(destination, "secret", publication))
            github.verify(destination, "secret", publication.copy(releaseId = 42))
            assertContentEquals(bytes, remote.files.getValue("tool.zip"))
            assertFalse(remote.draft)
            assertEquals(1, remote.creates)
            assertEquals(1, remote.uploads)
            assertEquals(1, remote.publications)
            assertEquals(0, remote.deletes)
            val upload = remote.requests.single { it.method == "POST" && it.url.encodedPath.endsWith("/assets") }
            assertEquals(bytes.size.toString(), upload.headers["Content-Length"])
            assertEquals("application/zip", upload.headers["Content-Type"])
            assertTrue(remote.requests.all { it.headers["Authorization"] == "Bearer secret" })
            assertEquals(42, github.publish(destination, "secret", publication.copy(releaseId = 42)))
            assertEquals(1, remote.uploads)
        }
    }

    @Test fun `canonical repository casing is accepted in the provider upload URL`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.uploadUrl = remote.base + "/repos/Acme/Tool/releases/42/assets"
            assertEquals(42, client(remote).publish(destination, "secret", publication))
            assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        }
    }

    @Test fun `a different repository or release in an upload URL is rejected`() = runBlocking {
        for (path in listOf("/repos/acme/other/releases/42/assets", "/repos/acme/tool/releases/43/assets")) {
            GitHubReleaseTestServer().use { remote ->
                remote.uploadUrl = remote.base + path
                assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
                assertEquals(0, remote.uploads)
            }
        }
    }

    @Test fun `annotated tags are resolved to the requested commit`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.annotated = true
            client(remote).publish(destination, "secret", publication)
            assertTrue(remote.requests.any { "/git/tags/" in it.url.encodedPath })
        }
    }

    @Test fun `repository recreation and tag mismatch fail before release creation`() = runBlocking {
        for (wrongRepository in listOf(true, false)) GitHubReleaseTestServer().use { remote ->
            if (wrongRepository) remote.repositoryId++ else remote.commit = "9".repeat(40)
            assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
            assertEquals(0, remote.creates)
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun `a lost successful upload response reuses the matching asset on retry`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.uploadFailures = 1
            remote.loseUploadResponse = true
            val github = client(remote)
            val failure = assertFailsWith<GitHubPublicationException> { github.publish(destination, "secret", publication) }
            assertFalse(failure.message.orEmpty().contains("credential"))
            assertTrue(remote.draft)
            assertEquals(42, github.publish(destination, "secret", publication))
            assertEquals(1, remote.creates)
            assertEquals(1, remote.uploads)
            assertEquals(0, remote.deletes)
        }
    }

    @Test fun `empty failed starter upload is removed only from the owned draft`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.uploadFailures = 1
            val github = client(remote)
            assertFailsWith<GitHubPublicationException> { github.publish(destination, "secret", publication) }
            assertEquals(42, github.publish(destination, "secret", publication))
            assertEquals(1, remote.deletes)
            assertEquals(2, remote.uploads)
        }
        GitHubReleaseTestServer().use { remote ->
            remote.hasRelease = true
            remote.marker = "somebody else's release"
            remote.starter = "tool.zip"
            assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
            assertEquals(0, remote.deletes)
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun `uploaded content mismatches are never deleted or overwritten`() = runBlocking {
        for (matchingSize in listOf(true, false)) GitHubReleaseTestServer().use { remote ->
            remote.hasRelease = true
            remote.draft = false
            remote.files["tool.zip"] = if (matchingSize) "wrong content".toByteArray() else byteArrayOf(1)
            assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
            assertEquals(0, remote.deletes)
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun `immutable releases cannot receive missing assets`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.hasRelease = true
            remote.draft = false
            remote.immutable = true
            assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
            assertEquals(0, remote.uploads)
        }
    }

    @Test fun `untrusted upload URLs are rejected before sending credentials or opening blobs`() = runBlocking {
        MockWebServer().use { foreign ->
            foreign.start()
            GitHubReleaseTestServer().use { remote ->
                remote.uploadUrl = foreign.url("/repos/acme/tool/releases/42/assets").toString()
                assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
                assertEquals(0, foreign.requestCount)
                coVerify(exactly = 0) { blobs.getInputStream(any()) }
            }
        }
    }

    @Test fun `API redirects are rejected instead of forwarding credentials`() = runBlocking {
        MockWebServer().use { remote ->
            remote.start()
            remote.enqueue(MockResponse.Builder().code(307).addHeader("Location", remote.url("/another-target")).build())
            val github = GitHubReleaseClient(blobs, apiUrl = remote.url("/").toString())
            assertFailsWith<GitHubPublicationException> { github.verifyRepository(destination, "secret") }
            assertEquals(1, remote.requestCount)
        }
    }

    @Test fun `verification detects changed assets and changed release identity`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            val github = client(remote)
            github.publish(destination, "secret", publication)
            assertFailsWith<GitHubPublicationException> { github.verify(destination, "secret", publication.copy(releaseId = 43)) }
            remote.assetDigests["tool.zip"] = "sha256:" + "0".repeat(64)
            assertFailsWith<GitHubPublicationException> { github.verify(destination, "secret", publication.copy(releaseId = 42)) }
            assertEquals(0, remote.deletes)
        }
    }

    @Test fun `renamed provider upload results fail verification while retaining the draft`() = runBlocking {
        GitHubReleaseTestServer().use { remote ->
            remote.assetNameOverride = "renamed.zip"
            assertFailsWith<GitHubPublicationException> { client(remote).publish(destination, "secret", publication) }
            assertTrue(remote.draft)
            assertEquals(0, remote.publications)
        }
    }
}
