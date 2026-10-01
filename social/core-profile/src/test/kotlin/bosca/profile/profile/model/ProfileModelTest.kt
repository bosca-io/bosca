package bosca.profile.profile.model

import bosca.profile.model.ProfileVisibility
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileModelTest {

    // -- ProfileInput --

    @Test
    fun `ProfileInput creation with all fields`() {
        val input = ProfileInput(
            slug = "john-doe",
            name = "John Doe",
            visibility = ProfileVisibility.PUBLIC
        )
        assertEquals("john-doe", input.slug)
        assertEquals("John Doe", input.name)
        assertEquals(ProfileVisibility.PUBLIC, input.visibility)
        assertTrue(input.attributes.isEmpty())
        assertNull(input.searchable)
    }

    @Test
    fun `ProfileInput stores searchable preference`() {
        val input = ProfileInput(
            name = "Jane",
            visibility = ProfileVisibility.PUBLIC,
            searchable = false,
        )

        assertEquals(false, input.searchable)
    }

    @Test
    fun `ProfileInput slug defaults to null`() {
        val input = ProfileInput(
            name = "Jane",
            visibility = ProfileVisibility.USER
        )
        assertNull(input.slug)
    }

    @Test
    fun `ProfileInput attributes defaults to empty list`() {
        val input = ProfileInput(
            name = "Test",
            visibility = ProfileVisibility.FRIENDS
        )
        assertTrue(input.attributes.isEmpty())
    }

    @Test
    fun `ProfileInput data class equality`() {
        val a = ProfileInput(name = "A", visibility = ProfileVisibility.SYSTEM)
        val b = ProfileInput(name = "A", visibility = ProfileVisibility.SYSTEM)
        assertEquals(a, b)
    }

    // -- ProfilePermission --

    @Test
    fun `ProfilePermission creation and field access`() {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val perm = ProfilePermission(
            entityId = entityId,
            groupId = groupId,
            action = PermissionAction.VIEW
        )
        assertEquals(entityId, perm.entityId)
        assertEquals(groupId, perm.groupId)
        assertEquals(PermissionAction.VIEW, perm.action)
    }

    @Test
    fun `ProfilePermission with different actions`() {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val manage = ProfilePermission(entityId = entityId, groupId = groupId, action = PermissionAction.MANAGE)
        assertEquals(PermissionAction.MANAGE, manage.action)
    }

    // -- ProfileMetadataTrait --

    @Test
    fun `ProfileMetadataTrait creation with all fields`() {
        val profileId = UUID.random()
        val trait = ProfileMetadataTrait(
            profileId = profileId,
            traitId = "trait-reading",
            value = "completed"
        )
        assertEquals(profileId, trait.profileId)
        assertEquals("trait-reading", trait.traitId)
        assertEquals("completed", trait.value)
    }

    @Test
    fun `ProfileMetadataTrait value defaults to null`() {
        val trait = ProfileMetadataTrait(
            profileId = UUID.random(),
            traitId = "trait-1"
        )
        assertNull(trait.value)
    }

    @Test
    fun `ProfileMetadataTrait data class equality`() {
        val id = UUID.random()
        val a = ProfileMetadataTrait(profileId = id, traitId = "t", value = "v")
        val b = ProfileMetadataTrait(profileId = id, traitId = "t", value = "v")
        assertEquals(a, b)
    }

    @Test
    fun `ProfileMetadataTrait copy changes value`() {
        val original = ProfileMetadataTrait(profileId = UUID.random(), traitId = "t", value = "old")
        val copy = original.copy(value = "new")
        assertEquals("new", copy.value)
        assertEquals(original.profileId, copy.profileId)
        assertEquals(original.traitId, copy.traitId)
    }
}
