package bosca.artifacts.sync

import bosca.artifacts.model.ArtifactBlob
import bosca.artifacts.model.ArtifactSyncDestination
import bosca.artifacts.service.BlobStorageService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import java.io.ByteArrayInputStream
import kotlin.test.*

class GhcrClientTest {
    private val fixture = ImageFixture()
    private val blobs = mockk<BlobStorageService>()
    private val destination = ArtifactSyncDestination(UUID.random(), UUID.random(), "ghcr", "acme/server", "acme", "ghcr-token", true)

    private fun client(remote: GhcrTestServer): GhcrClient {
        coEvery { blobs.get(any()) } coAnswers {
            val digest = firstArg<String>()
            fixture.bytes[digest]?.let { ArtifactBlob(digest, it.size.toLong()) }
        }
        coEvery { blobs.getInputStream(any()) } coAnswers { ByteArrayInputStream(fixture.bytes.getValue(firstArg())) }
        return GhcrClient(blobs, registryUrl = remote.base)
    }

    @Test
    fun `copies multi-platform graphs with original bytes then assigns and verifies the source tag`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            assertTrue(client(remote).push(destination, "credential", "v1", fixture.index, null) { true })
            assertEquals(fixture.index, remote.tags["v1"])
            for (digest in listOf(fixture.manifest, fixture.otherManifest, fixture.index)) {
                assertContentEquals(fixture.bytes[digest], remote.manifests[digest])
            }
            for (digest in listOf(fixture.config, fixture.otherConfig, fixture.layer)) {
                assertContentEquals(fixture.bytes[digest], remote.blobs[digest])
            }
            val token = remote.requests.first()
            assertEquals(Credentials.basic("acme", "credential"), token.headers["Authorization"])
            assertEquals("repository:acme/server:pull,push", token.url.queryParameter("scope"))
            assertTrue(remote.requests.drop(1).all { it.headers["Authorization"] == "Bearer registry-token" })
            assertEquals("application/vnd.oci.image.index.v1+json", remote.requests.single {
                it.method == "PUT" && it.url.encodedPath.endsWith("/manifests/v1")
            }.headers["Content-Type"])
        }
    }

    @Test
    fun `OCI roots without mediaType preserve their stored type and original bytes`(): Unit = runBlocking {
        for ((source, mediaType) in listOf(
            fixture.manifest to "application/vnd.oci.image.manifest.v1+json",
            fixture.index to "application/vnd.oci.image.index.v1+json",
        )) {
            val root = fixture.withoutMediaType(source)
            GhcrTestServer().use { remote ->
                assertTrue(client(remote).push(destination, "credential", "latest", root, "$mediaType; charset=utf-8") { true })
                assertEquals(root, remote.tags["latest"])
                assertContentEquals(fixture.bytes[root], remote.manifests[root])
                assertTrue(remote.requests.filter {
                    it.method == "PUT" && it.url.encodedPath.endsWith("/manifests/$root") ||
                            it.method == "PUT" && it.url.encodedPath.endsWith("/manifests/latest")
                }.all { it.headers["Content-Type"] == mediaType })
            }
        }
    }

    @Test
    fun `foreign layers keep their external URLs without fetching or uploading even locally cached content`(): Unit = runBlocking {
        val layerBytes = fixture.bytes.getValue(fixture.layer)
        for (type in listOf(
            "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip",
            "application/vnd.oci.image.layer.nondistributable.v1.tar",
            "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
            "application/vnd.oci.image.layer.nondistributable.v1.tar+zstd",
        )) {
            fixture.bytes[fixture.layer] = layerBytes
            val manifestType = if (type.startsWith("application/vnd.docker."))
                "application/vnd.docker.distribution.manifest.v2+json" else "application/vnd.oci.image.manifest.v1+json"
            val root = fixture.withLayer(type, listOf("https://example.org/base-layer"), manifestType)
            for (cached in listOf(false, true)) {
                if (cached) fixture.bytes[fixture.layer] = layerBytes else fixture.bytes.remove(fixture.layer)
                GhcrTestServer().use { remote ->
                    assertTrue(client(remote).push(destination, "credential", "latest", root, null) { true })
                    assertEquals(root, remote.tags["latest"])
                    assertContentEquals(fixture.bytes[root], remote.manifests[root])
                    assertFalse(remote.blobs.containsKey(fixture.layer))
                    assertFalse(remote.requests.any { it.url.pathSegments.last() == fixture.layer })
                }
            }
        }
        coVerify(exactly = 0) { blobs.get(fixture.layer) }
        coVerify(exactly = 0) { blobs.getInputStream(fixture.layer) }
    }

    @Test
    fun `ordinary layers with URLs still require their stored content`(): Unit = runBlocking {
        val root = fixture.withLayer("application/vnd.oci.image.layer.v1.tar", listOf("https://example.org/ordinary-layer"))
        fixture.bytes.remove(fixture.layer)
        GhcrTestServer().use { remote ->
            assertFailsWith<GhcrException> { client(remote).push(destination, "credential", "latest", root, null) { true } }
            assertTrue(remote.requests.isEmpty())
        }
    }

    @Test
    fun `non-distributable layers without external URLs still require stored content`(): Unit = runBlocking {
        val root = fixture.withLayer("application/vnd.docker.image.rootfs.foreign.diff.tar.gzip")
        fixture.bytes.remove(fixture.layer)
        GhcrTestServer().use { remote ->
            assertFailsWith<GhcrException> { client(remote).push(destination, "credential", "latest", root, null) { true } }
            assertTrue(remote.requests.isEmpty())
        }
    }

    @Test
    fun `response loss retries reuse uploaded blobs without assigning an incomplete image`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            val client = client(remote)
            remote.uploadFailures = 1
            remote.loseUploadResponse = true
            val failure = assertFailsWith<GhcrException> { client.push(destination, "credential", "latest", fixture.index, null) { true } }
            assertFalse(failure.message.orEmpty().contains("provider-secret"))
            assertTrue(remote.tags.isEmpty())
            assertTrue(client.push(destination, "credential", "latest", fixture.index, null) { true })
            assertEquals(3, remote.requests.count { it.method == "PUT" && "/blobs/uploads/" in it.url.encodedPath })
        }
    }

    @Test
    fun `superseded requests leave the remote tag untouched`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            assertFalse(client(remote).push(destination, "credential", "latest", fixture.index, null) { false })
            assertTrue(remote.tags.isEmpty())
            assertTrue(remote.manifests.isNotEmpty())
        }
    }

    @Test
    fun `missing local dependencies fail before making any registry request`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            val client = client(remote)
            fixture.bytes.remove(fixture.layer)
            assertFailsWith<GhcrException> { client.push(destination, "credential", "v1", fixture.index, null) { true } }
            assertTrue(remote.requests.isEmpty())
        }
    }

    @Test
    fun `upload URLs cannot redirect credentials to another origin`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            remote.uploadLocation = "https://unexpected.example/upload"
            assertFailsWith<GhcrException> { client(remote).push(destination, "credential", "v1", fixture.index, null) { true } }
            assertEquals(3, remote.requests.size)
        }
    }

    @Test
    fun `verification rejects a different remote digest`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            remote.verificationDigest = "sha256:" + "0".repeat(64)
            assertFailsWith<GhcrException> { client(remote).push(destination, "credential", "v1", fixture.index, null) { true } }
        }
    }

    @Test
    fun `cancellation propagates without assigning a tag`(): Unit = runBlocking {
        GhcrTestServer().use { remote ->
            assertFailsWith<CancellationException> {
                client(remote).push(destination, "credential", "v1", fixture.index, null) { throw CancellationException("cancel") }
            }
            assertTrue(remote.tags.isEmpty())
        }
    }
}
