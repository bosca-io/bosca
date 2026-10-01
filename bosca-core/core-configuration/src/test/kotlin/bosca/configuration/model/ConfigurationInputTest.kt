package bosca.configuration.model

import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Validates the [ConfigurationInput] data class used to create or update
 * configuration entries via the GraphQL mutation layer.
 */
class ConfigurationInputTest {

    @Test
    fun `ConfigurationInput stores all fields`() {
        val groupId = Uuid.random()
        val input = ConfigurationInput(
            key = "smtp.host",
            description = "SMTP server host",
            value = JsonPrimitive("mail.example.com"),
            public = false,
            permissions = listOf(
                PermissionInput(action = PermissionAction.VIEW, entityId = Uuid.NIL, groupId = groupId)
            )
        )
        assertEquals("smtp.host", input.key)
        assertEquals("SMTP server host", input.description)
        assertEquals(JsonPrimitive("mail.example.com"), input.value)
        assertEquals(false, input.public)
        assertEquals(1, input.permissions.size)
        assertEquals(PermissionAction.VIEW, input.permissions.first().action)
    }

    @Test
    fun `ConfigurationInput with empty permissions list`() {
        val input = ConfigurationInput(
            key = "key",
            description = "desc",
            value = JsonPrimitive(42),
            public = true,
            permissions = emptyList()
        )
        assertTrue(input.permissions.isEmpty())
        assertTrue(input.public)
    }

    @Test
    fun `ConfigurationInput with multiple permissions`() {
        val group1 = Uuid.random()
        val group2 = Uuid.random()
        val input = ConfigurationInput(
            key = "api.key",
            description = "API Key",
            value = JsonPrimitive("secret"),
            public = false,
            permissions = listOf(
                PermissionInput(action = PermissionAction.VIEW, entityId = Uuid.NIL, groupId = group1),
                PermissionInput(action = PermissionAction.MANAGE, entityId = Uuid.NIL, groupId = group2)
            )
        )
        assertEquals(2, input.permissions.size)
    }

    @Test
    fun `ConfigurationInput data class equality`() {
        val groupId = Uuid.random()
        val perms = listOf(PermissionInput(action = PermissionAction.VIEW, entityId = Uuid.NIL, groupId = groupId))
        val i1 = ConfigurationInput("k", "d", JsonPrimitive(true), false, perms)
        val i2 = ConfigurationInput("k", "d", JsonPrimitive(true), false, perms)
        assertEquals(i1, i2)
    }

    @Test
    fun `ConfigurationInput data class copy`() {
        val input = ConfigurationInput(
            key = "old",
            description = "desc",
            value = JsonPrimitive("val"),
            public = false,
            permissions = emptyList()
        )
        val modified = input.copy(key = "new", public = true)
        assertEquals("new", modified.key)
        assertTrue(modified.public)
        assertEquals("desc", modified.description)
    }
}
