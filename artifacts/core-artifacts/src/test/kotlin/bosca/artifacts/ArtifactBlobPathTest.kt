package bosca.artifacts

import bosca.artifacts.model.ArtifactBlobPath
import bosca.artifacts.model.UploadChunkPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactBlobPathTest {

    @Test
    fun `sha256 digest produces correct path`() {
        val path = ArtifactBlobPath("sha256:abcdef1234567890")
        assertEquals("artifacts/blobs/sha256/ab/abcdef1234567890", path.toString())
    }

    @Test
    fun `different prefixes produce different directories`() {
        val pathA = ArtifactBlobPath("sha256:aabbccdd")
        val pathB = ArtifactBlobPath("sha256:11223344")
        assertEquals("artifacts/blobs/sha256/aa/aabbccdd", pathA.toString())
        assertEquals("artifacts/blobs/sha256/11/11223344", pathB.toString())
    }

    @Test
    fun `invalid digest format throws`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactBlobPath("nocolon")
        }
    }

    @Test
    fun `digest hex too short throws`() {
        assertFailsWith<IllegalArgumentException> {
            ArtifactBlobPath("sha256:a")
        }
    }

    @Test
    fun `upload chunk path format`() {
        val path = UploadChunkPath("session-uuid-123", 0)
        assertEquals("artifacts/uploads/session-uuid-123/0", path.toString())
    }

    @Test
    fun `upload chunk path with higher index`() {
        val path = UploadChunkPath("session-uuid-123", 5)
        assertEquals("artifacts/uploads/session-uuid-123/5", path.toString())
    }
}
