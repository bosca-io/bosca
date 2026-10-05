package bosca.profile.bookmark.graphql

import bosca.profile.bookmark.model.ProfileBookmark
import bosca.serialization.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileBookmarkControllerTest {

    @Test
    fun `id returns bookmark id`() {
        val bookmark = ProfileBookmark(id = 42, profileId = UUID.random())
        val controller = createController()

        assertEquals(42L, controller.id(bookmark))
    }

    @Test
    fun `created returns bookmark created timestamp`() {
        val bookmark = ProfileBookmark(id = 1, profileId = UUID.random())
        val controller = createController()

        assertEquals(bookmark.created, controller.created(bookmark))
    }

    @Test
    fun `attributes returns bookmark attributes`() {
        val attrs = buildJsonObject { put("key", "value") }
        val bookmark = ProfileBookmark(id = 1, profileId = UUID.random(), attributes = attrs)
        val controller = createController()

        assertEquals(attrs, controller.attributes(bookmark))
    }

    @Test
    fun `attributes returns null when bookmark has no attributes`() {
        val bookmark = ProfileBookmark(id = 1, profileId = UUID.random())
        val controller = createController()

        assertNull(controller.attributes(bookmark))
    }

    private fun createController(): ProfileBookmarkController {
        return ProfileBookmarkController(
            metadataService = io.mockk.mockk(),
            collectionService = io.mockk.mockk(),
            metadataPermissionEvaluator = io.mockk.mockk(),
            collectionPermissionEvaluator = io.mockk.mockk()
        )
    }
}
