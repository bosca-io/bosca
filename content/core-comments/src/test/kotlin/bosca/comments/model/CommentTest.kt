package bosca.comments.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import java.time.OffsetDateTime

/**
 * Pure model assertions for [Comment]. The model is metadata-keyed
 * to mirror the Rust counterpart in
 * `workspace/core/server/src/models/content/comment.rs` —
 * `metadataId` and `version` are required, attributes /
 * systemAttributes default null, likes default 0.
 */
class CommentTest {

    private fun fixture(
        id: Long = 1L,
        metadataId: Uuid = Uuid.random(),
        version: Int = 1,
        profileId: Uuid = Uuid.random(),
        status: CommentStatus = CommentStatus.PENDING,
        content: String = "Hello",
    ): Comment {
        val now = OffsetDateTime.now()
        return Comment(
            id = id,
            metadataId = metadataId,
            version = version,
            profileId = profileId,
            created = now,
            modified = now,
            status = status,
            content = content,
        )
    }

    @Test
    fun `Comment stores all required fields`() {
        val profileId = Uuid.random()
        val metadataId = Uuid.random()
        val comment = fixture(
            id = 1L,
            metadataId = metadataId,
            version = 2,
            profileId = profileId,
            status = CommentStatus.APPROVED,
            content = "Great article!",
        ).copy(likes = 5)
        assertEquals(1L, comment.id)
        assertEquals(metadataId, comment.metadataId)
        assertEquals(2, comment.version)
        assertEquals(profileId, comment.profileId)
        assertEquals(CommentStatus.APPROVED, comment.status)
        assertEquals("Great article!", comment.content)
        assertEquals(5, comment.likes)
    }

    @Test
    fun `Comment optional fields default to null - attributes systemAttributes`() {
        val comment = fixture()
        assertNull(comment.attributes)
        assertNull(comment.systemAttributes)
        assertEquals(0, comment.likes)
    }

    @Test
    fun `Comment with attributes`() {
        val attrs = JsonObject(mapOf("mood" to JsonPrimitive("positive")))
        val comment = fixture().copy(attributes = attrs)
        assertEquals(attrs, comment.attributes)
    }

    @Test
    fun `Comment data class equality`() {
        val mid = Uuid.random()
        val pid = Uuid.random()
        val c1 = fixture(metadataId = mid, profileId = pid)
        val c2 = c1.copy()
        assertEquals(c1, c2)
    }

    @Test
    fun `Comment data class copy`() {
        val comment = fixture(content = "Original", status = CommentStatus.PENDING)
        val modified = comment.copy(status = CommentStatus.APPROVED, likes = 10)
        assertEquals(CommentStatus.APPROVED, modified.status)
        assertEquals(10, modified.likes)
        assertEquals("Original", modified.content)
    }
}
