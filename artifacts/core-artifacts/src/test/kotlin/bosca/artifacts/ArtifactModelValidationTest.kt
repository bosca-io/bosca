package bosca.artifacts

import bosca.artifacts.model.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * Verifies that artifact model types enforce their construction-time invariants,
 * rejecting invalid names, digests, and other fields that could cause downstream
 * issues in scope parsing, storage paths, or database integrity.
 */
class ArtifactModelValidationTest {

    private val id = Uuid.random()

    // -- ArtifactNamespace --

    @Test
    fun `namespace rejects colon in name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactNamespace(id = id, name = "bad:name")
        }
    }

    @Test
    fun `namespace rejects slash in name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactNamespace(id = id, name = "bad/name")
        }
    }

    @Test
    fun `namespace rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactNamespace(id = id, name = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ArtifactNamespace(id = id, name = "   ")
        }
    }

    @Test
    fun `namespace accepts valid names`() {
        ArtifactNamespace(id = id, name = "acme")
        ArtifactNamespace(id = id, name = "@scope")
        ArtifactNamespace(id = id, name = "com.example")
    }

    // -- ArtifactRepository --

    @Test
    fun `repository rejects colon in name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactRepository(id = id, namespaceId = id, name = "bad:name", type = "docker")
        }
    }

    @Test
    fun `repository rejects slash in name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactRepository(id = id, namespaceId = id, name = "bad/name", type = "docker")
        }
    }

    @Test
    fun `repository rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactRepository(id = id, namespaceId = id, name = "", type = "docker")
        }
    }

    @Test
    fun `repository accepts valid names`() {
        ArtifactRepository(id = id, namespaceId = id, name = "my-api", type = "docker")
        ArtifactRepository(id = id, namespaceId = id, name = "sdk_v2", type = "maven")
        ArtifactRepository(id = id, namespaceId = id, name = "cli", type = "npm")
    }

    // -- ArtifactTag --

    @Test
    fun `tag rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactTag(id = id, repositoryId = id, name = "", manifestDigest = "sha256:abc")
        }
    }

    @Test
    fun `tag rejects blank manifest digest`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactTag(id = id, repositoryId = id, name = "latest", manifestDigest = "")
        }
    }

    @Test
    fun `tag accepts valid values`() {
        ArtifactTag(id = id, repositoryId = id, name = "latest", manifestDigest = "sha256:abc123")
        ArtifactTag(id = id, repositoryId = id, name = "v1.0", manifestDigest = "sha256:def456")
    }

    // -- ArtifactVersion --

    @Test
    fun `version rejects blank version string`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactVersion(id = id, repositoryId = id, version = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ArtifactVersion(id = id, repositoryId = id, version = "   ")
        }
    }

    @Test
    fun `version accepts valid version strings`() {
        ArtifactVersion(id = id, repositoryId = id, version = "1.0.0")
        ArtifactVersion(id = id, repositoryId = id, version = "sha256:abc123")
        ArtifactVersion(id = id, repositoryId = id, version = "latest")
    }

    // -- ArtifactVersionBlob --

    @Test
    fun `version blob rejects blank digest`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactVersionBlob(versionId = id, digest = "", role = "manifest")
        }
    }

    @Test
    fun `version blob rejects blank role`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactVersionBlob(versionId = id, digest = "sha256:abc", role = "")
        }
    }

    @Test
    fun `version blob accepts valid values`() {
        ArtifactVersionBlob(versionId = id, digest = "sha256:abc123", role = "manifest")
        ArtifactVersionBlob(versionId = id, digest = "sha256:def456", role = "layer", filename = "data.tar.gz")
    }

    // -- ArtifactBlob --

    @Test
    fun `blob default refCount is 0 matching SQL schema`() {
        val blob = ArtifactBlob(digest = "sha256:abc", size = 100)
        kotlin.test.assertEquals(0, blob.refCount)
    }
}
