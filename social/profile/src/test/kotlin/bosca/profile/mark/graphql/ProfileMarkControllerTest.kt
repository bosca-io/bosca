package bosca.profile.mark.graphql

import bosca.profile.mark.model.ProfileMark
import bosca.serialization.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileMarkControllerTest {

    private val controller = ProfileMarkController(
        metadataService = io.mockk.mockk(),
        metadataPermissionEvaluator = io.mockk.mockk(),
        collectionService = io.mockk.mockk(),
        collectionPermissionEvaluator = io.mockk.mockk()
    )

    @Test
    fun `id returns mark id`() {
        val mark = ProfileMark(id = 42, profileId = UUID.random())
        assertEquals(42L, controller.id(mark))
    }

    @Test
    fun `created returns mark created timestamp`() {
        val mark = ProfileMark(id = 1, profileId = UUID.random())
        assertEquals(mark.created, controller.created(mark))
    }

    @Test
    fun `attributes returns mark attributes`() {
        val attrs = buildJsonObject { put("highlight", "text") }
        val mark = ProfileMark(id = 1, profileId = UUID.random(), attributes = attrs)
        assertEquals(attrs, controller.attributes(mark))
    }

    @Test
    fun `attributes returns null when mark has no attributes`() {
        val mark = ProfileMark(id = 1, profileId = UUID.random())
        assertNull(controller.attributes(mark))
    }
}
