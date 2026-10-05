package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataRelationshipControllerTest {

    private val service = mockk<MetadataService>()
    private val controller = MetadataRelationshipController(service)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `id returns metadataId1`() {
        val id1 = UUID.random()
        val relationship = MetadataRelationship(
            metadataId1 = id1,
            metadataId2 = UUID.random(),
            relationship = "related"
        )

        assertEquals(id1, controller.id(relationship))
    }

    @Test
    fun `relationship returns relationship string`() {
        val relationship = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "translation"
        )

        assertEquals("translation", controller.relationship(relationship))
    }

    @Test
    fun `attributes returns attributes from relationship`() {
        val attrs = JsonObject(mapOf("type" to JsonPrimitive("audio")))
        val relationship = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "related",
            attributes = attrs
        )

        assertEquals(attrs, controller.attributes(relationship))
    }

    @Test
    fun `attributes returns null when not set`() {
        val relationship = MetadataRelationship(
            metadataId1 = UUID.random(),
            metadataId2 = UUID.random(),
            relationship = "related"
        )

        assertNull(controller.attributes(relationship))
    }
}
