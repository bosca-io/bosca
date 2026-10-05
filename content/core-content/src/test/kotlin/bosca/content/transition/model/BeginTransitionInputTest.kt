package bosca.content.transition.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BeginTransitionInputTest {

    @Test
    fun `BeginTransitionInput stores required fields`() {
        val input = BeginTransitionInput(
            stateId = "published",
            status = "transitioning"
        )
        assertEquals("published", input.stateId)
        assertEquals("transitioning", input.status)
    }

    @Test
    fun `BeginTransitionInput nullable fields default to null`() {
        val input = BeginTransitionInput(stateId = "s", status = "st")
        assertNull(input.collectionId)
        assertNull(input.metadataId)
        assertNull(input.version)
        assertNull(input.supplementaryId)
        assertNull(input.restart)
        assertNull(input.stateValid)
        assertNull(input.configuration)
        assertNull(input.languageTag)
    }

    @Test
    fun `BeginTransitionInput boolean defaults`() {
        val input = BeginTransitionInput(stateId = "s", status = "st")
        assertFalse(input.allowProcessing)
    }

    @Test
    fun `BeginTransitionInput stores all properties`() {
        val collId = Uuid.random()
        val metaId = Uuid.random()
        val supId = Uuid.random()
        val config = buildJsonObject { put("param", "value") }

        val input = BeginTransitionInput(
            collectionId = collId,
            metadataId = metaId,
            version = 2,
            supplementaryId = supId,
            restart = true,
            stateId = "approved",
            status = "approving",
            configuration = config,
            languageTag = "fr",
            allowProcessing = true
        )

        assertEquals(collId, input.collectionId)
        assertEquals(metaId, input.metadataId)
        assertEquals(2, input.version)
        assertEquals(supId, input.supplementaryId)
        assertEquals(true, input.restart)
        assertEquals("approved", input.stateId)
        assertEquals("approving", input.status)
        assertEquals(config, input.configuration)
        assertEquals("fr", input.languageTag)
        assertEquals(true, input.allowProcessing)
    }

    @Test
    fun `BeginTransitionInput data class equality`() {
        val input1 = BeginTransitionInput(stateId = "s", status = "st")
        val input2 = BeginTransitionInput(stateId = "s", status = "st")
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `BeginTransitionInput copy preserves unchanged fields`() {
        val metaId = Uuid.random()
        val input = BeginTransitionInput(
            metadataId = metaId,
            version = 1,
            stateId = "draft",
            status = "starting"
        )
        val copied = input.copy(stateId = "published")
        assertEquals("published", copied.stateId)
        assertEquals(metaId, copied.metadataId)
        assertEquals(1, copied.version)
        assertEquals("starting", copied.status)
    }
}
