package bosca.content.collection.model

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CollectionCollaborationTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `stores all properties`() {
        val collectionId = Uuid.random()
        val content = byteArrayOf(1, 2, 3, 4)

        val collab = CollectionCollaboration(
            collectionId = collectionId,
            languageTag = "en-US",
            content = content,
            created = now,
            modified = now
        )

        assertEquals(collectionId, collab.collectionId)
        assertEquals("en-US", collab.languageTag)
        assertTrue(content.contentEquals(collab.content))
        assertEquals(now, collab.created)
        assertEquals(now, collab.modified)
    }

    @Test
    fun `default values`() {
        val collab = CollectionCollaboration()

        assertEquals(Uuid.NIL, collab.collectionId)
        assertEquals("", collab.languageTag)
        assertEquals(0, collab.content.size)
        assertNull(collab.created)
        assertNull(collab.modified)
    }

    @Test
    fun `CollectionCollaboration is not a data class so uses reference equality`() {
        val id = Uuid.random()
        val a = CollectionCollaboration(collectionId = id, languageTag = "en")
        val b = CollectionCollaboration(collectionId = id, languageTag = "en")
        // Not a data class, so two instances with same fields are not equal
        assertTrue(a !== b)
    }
}
