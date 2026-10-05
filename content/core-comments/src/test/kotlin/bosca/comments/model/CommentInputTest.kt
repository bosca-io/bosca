package bosca.comments.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentInputTest {

    @Test
    fun `CommentInput with null optional fields`() {
        val input = CommentInput(
            parentId = null,
            visibility = ProfileVisibility.USER,
            content = "Root comment",
            attributes = null,
            systemAttributes = null,
            impersonateId = null
        )
        assertNull(input.parentId)
        assertNull(input.attributes)
        assertNull(input.systemAttributes)
        assertNull(input.impersonateId)
    }

    @Test
    fun `CommentInput with friends visibility`() {
        val input = CommentInput(
            parentId = null,
            visibility = ProfileVisibility.FRIENDS,
            content = "Friends only",
            attributes = null,
            systemAttributes = null,
            impersonateId = null
        )
        assertEquals(ProfileVisibility.FRIENDS, input.visibility)
    }

    @Test
    fun `CommentInput visibility defaults to null when omitted`() {
        // null means "not specified" — the addComment resolver then falls back to
        // the author's profile visibility (or a manager's explicit choice).
        val input = CommentInput(
            parentId = null,
            content = "no visibility specified",
            attributes = null,
            systemAttributes = null,
            impersonateId = null,
        )
        assertNull(input.visibility)
    }

    @Test
    fun `CommentInput data class equality`() {
        val i1 = CommentInput(
            parentId = 1L, visibility = ProfileVisibility.PUBLIC,
            content = "Test", attributes = null, systemAttributes = null, impersonateId = null
        )
        val i2 = CommentInput(
            parentId = 1L, visibility = ProfileVisibility.PUBLIC,
            content = "Test", attributes = null, systemAttributes = null, impersonateId = null
        )
        assertEquals(i1, i2)
    }
}
