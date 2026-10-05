package bosca.profile.guide.graphql

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileGuideHistoryControllerTest {

    private val controller = ProfileGuideHistoryController(
        metadataService = io.mockk.mockk(),
        metadataPermissionEvaluator = io.mockk.mockk()
    )

    @Test
    fun `attributes returns history attributes`() {
        val attrs = buildJsonObject { put("key", "value") }
        val history = ProfileGuideHistory(
            id = 1,
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = attrs
        )

        assertEquals(attrs, controller.attributes(history))
    }

    @Test
    fun `completed returns history completed timestamp`() {
        val history = ProfileGuideHistory(
            id = 1,
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = JsonObject(emptyMap())
        )

        assertEquals(history.completed, controller.completed(history))
    }

    @Test
    fun `completed returns null when history has no completed timestamp`() {
        val history = ProfileGuideHistory(
            id = 1,
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = JsonObject(emptyMap()),
            completed = null
        )

        assertNull(controller.completed(history))
    }
}
