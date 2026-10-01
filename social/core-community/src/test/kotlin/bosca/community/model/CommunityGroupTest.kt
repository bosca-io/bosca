package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CommunityGroupTest {

    private val groupId = Uuid.random()

    // --- PUBLIC visibility permission flags ---

    @Test
    fun `PUBLIC group has public flags set to true`() {
        val group = CommunityGroup(
            id = groupId, name = "Open Group", description = "Visible to all",
            type = CommunityGroupType.CUSTOM, visibility = CommunityVisibility.PUBLIC
        )
        assertTrue(group.public)
        assertTrue(group.publicContent)
        assertTrue(group.publicList)
    }

    // --- PRIVATE visibility permission flags ---

    @Test
    fun `PRIVATE group has public flags set to false`() {
        val group = CommunityGroup(
            id = groupId, name = "Private", description = "Hidden",
            type = CommunityGroupType.SMALL_GROUP, visibility = CommunityVisibility.PRIVATE
        )
        assertFalse(group.public)
        assertFalse(group.publicContent)
        assertFalse(group.publicList)
    }

    // --- HIDDEN visibility permission flags ---

    @Test
    fun `HIDDEN group has public flags set to false`() {
        val group = CommunityGroup(
            id = groupId, name = "Secret", description = "Secret group",
            type = CommunityGroupType.FAMILY, visibility = CommunityVisibility.HIDDEN
        )
        assertFalse(group.public)
        assertFalse(group.publicContent)
        assertFalse(group.publicList)
    }

    // --- Common defaults ---

    @Test
    fun `CommunityGroup supplementary is always false`() {
        val group = CommunityGroup(
            id = groupId, name = "G", description = "d",
            type = CommunityGroupType.CUSTOM, visibility = CommunityVisibility.PUBLIC
        )
        assertFalse(group.publicSupplementary)
    }

    @Test
    fun `CommunityGroup isPublished and isAdvertised are true`() {
        val group = CommunityGroup(
            id = groupId, name = "G", description = "d",
            type = CommunityGroupType.CUSTOM, visibility = CommunityVisibility.PUBLIC
        )
        assertTrue(group.isPublished)
        assertTrue(group.isAdvertised)
        assertFalse(group.isDeleted)
    }

    @Test
    fun `CommunityGroup attributes defaults to null`() {
        val group = CommunityGroup(
            id = groupId, name = "G", description = "d",
            type = CommunityGroupType.CUSTOM, visibility = CommunityVisibility.PRIVATE
        )
        assertNull(group.attributes)
    }
}
